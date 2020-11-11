package cluster;

import static akka.http.javadsl.server.Directives.complete;
import static akka.http.javadsl.server.Directives.concat;
import static akka.http.javadsl.server.Directives.entity;
import static akka.http.javadsl.server.Directives.getFromResource;
import static akka.http.javadsl.server.Directives.handleWebSocketMessages;
import static akka.http.javadsl.server.Directives.onSuccess;
import static akka.http.javadsl.server.Directives.path;
import static akka.http.javadsl.server.Directives.post;

import java.io.Serializable;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;

import akka.NotUsed;
import akka.actor.typed.ActorSystem;
import akka.cluster.MemberStatus;
import akka.cluster.sharding.typed.javadsl.ClusterSharding;
import akka.cluster.typed.Cluster;
import akka.cluster.typed.Leave;
import akka.http.javadsl.Http;
import akka.http.javadsl.marshallers.jackson.Jackson;
import akka.http.javadsl.model.ContentTypes;
import akka.http.javadsl.model.MediaTypes;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.model.ws.Message;
import akka.http.javadsl.model.ws.TextMessage;
import akka.http.javadsl.server.Route;
import akka.japi.JavaPartialFunction;
import akka.stream.javadsl.Flow;
import cluster.EntityActor.Value;
import cluster.EntityCommand.ChangeValueAck;
import cluster.EntityCommand.GetValueAck;
import cluster.HttpServer.ClientActivitySummary.ClientActivity;
import cluster.HttpServer.ServerActivitySummary.ServerActivity;
import cluster.IpId.Client;
import cluster.IpId.Server;

class HttpServer {
  private final ActorSystem<?> actorSystem;
  private final ClusterSharding clusterSharding;
  private final IpId.Server httpServer;
  private final Tree tree = new Tree("cluster", "cluster");
  private final ActivitySummary activitySummary = new ActivitySummary();

  static HttpServer start(String host, int port, ActorSystem<?> actorSystem) {
    return new HttpServer(host, port, actorSystem);
  }

  private HttpServer(String host, int port, ActorSystem<?> actorSystem) {
    this.actorSystem = actorSystem;
    clusterSharding = ClusterSharding.get(actorSystem);
    httpServer = IpId.Server.of(actorSystem);

    start(host, port);
  }

  private void start(String host, int port) {
    Http.get(actorSystem).newServerAt(host, port).bind(route());
    log().info("HTTP Server started on {}:{}", host, "" + port);
  }

  private Route route() {
    return concat(
        path("entity-change", this::handleEntityChange),
        path("entity-query", this::handleEntityQuery),
        path("", () -> getFromResource("viewer.html", ContentTypes.TEXT_HTML_UTF8)),
        path("viewer", () -> getFromResource("viewer.html", ContentTypes.TEXT_HTML_UTF8)),
        path("viewer.html", () -> getFromResource("viewer.html", ContentTypes.TEXT_HTML_UTF8)),
        path("viewer.js", () -> getFromResource("viewer.js", ContentTypes.APPLICATION_JSON)),
        path("d3.v5.js", () -> getFromResource("d3.v5.js", MediaTypes.APPLICATION_JAVASCRIPT.toContentTypeWithMissingCharset())),
        path("viewer-entities", () -> handleWebSocketMessages(handleClientMessages())),
        path("favicon.ico", () -> getFromResource("favicon.ico", MediaTypes.IMAGE_X_ICON.toContentType()))
    );
  }

  private Route handleEntityChange() {
    return post(
        () -> entity(
            Jackson.unmarshaller(EntityCommand.ChangeValue.class),
            changeValue -> {
              EntityActor.Id id = new EntityActor.Id(changeValue.id);
              EntityActor.Value value = new EntityActor.Value(changeValue.value);
              return onSuccess(submitChangeValue(new EntityActor.ChangeValue(id, value, null, changeValue.httpClient, httpServer)),
                  changeValueAck -> {
                    final ChangeValueAck ack = new ChangeValueAck(id.id, value.value, changeValue.nsStart, changeValueAck.action, StatusCodes.ACCEPTED.intValue());
                    return complete(StatusCodes.ACCEPTED, ack, Jackson.marshaller());
                  });
            }
        )
    );
  }

  private CompletionStage<EntityActor.ChangeValueAck> submitChangeValue(EntityActor.ChangeValue changeValue) {
    final var entityId = changeValue.id.id;
    final var entityRef = clusterSharding.entityRefFor(EntityActor.entityTypeKey, entityId);
    return entityRef.ask(changeValue::replyTo, Duration.ofSeconds(30))
        .handle((reply, e) -> {
          if (reply != null) {
            return new EntityActor.ChangeValueAck("changed", changeValue.id, changeValue.value);
          } else {
            return new EntityActor.ChangeValueAck(e.getMessage(), changeValue.id, changeValue.value);
          }
        });
  }

  private Route handleEntityQuery() {
    return post(
        () -> entity(
            Jackson.unmarshaller(EntityCommand.GetValue.class),
            getValue -> {
              EntityActor.Id id = new EntityActor.Id(getValue.id);
              return onSuccess(submitGetValue(new EntityActor.GetValue(id, null, getValue.httpClient, httpServer)),
                  getValueAck -> {
                    GetValueAck ack = new GetValueAck(id.id, getValueAck.value.value, getValue.nsStart, "success", StatusCodes.ACCEPTED.intValue());
                    return complete(StatusCodes.ACCEPTED, ack, Jackson.marshaller());
                  });
            }
        )
    );
  }

  private CompletionStage<EntityActor.GetValueAck> submitGetValue(EntityActor.GetValue getValue) {
    final var entityId = getValue.id.id;
    final var entityRef = clusterSharding.entityRefFor(EntityActor.entityTypeKey, entityId);
    return entityRef.ask(getValue::replyTo, Duration.ofSeconds(30))
        .handle((reply, e) -> {
          if (reply != null) {
            return new EntityActor.GetValueAck(getValue.id, 
              new Value(reply instanceof EntityActor.GetValueAck 
                ? ((EntityActor.GetValueAck) reply).value.value
                : "Not found"));
          } else {
            return new EntityActor.GetValueAck(getValue.id, new Value(e.getMessage()));
          }
        });
  }

  private Flow<Message, Message, NotUsed> handleClientMessages() {
    return Flow.<Message>create().collect(new JavaPartialFunction<Message, Message>() {
      @Override
      public Message apply(Message message, boolean isCheck) {
        if (isCheck && message.isText()) {
          return null;
        } else if (isCheck && !message.isText()) {
          throw noMatch();
        } else if (message.asTextMessage().isStrict()) {
          return handleClientMessage(message);
        } else {
          return TextMessage.create("");
        }
      }
    });
  }

  private Message handleClientMessage(Message message) {
    final var messageText = message.asTextMessage().getStrictText();
    if (messageText.startsWith("akka://")) {
      handleStopNode(messageText);
    }
    return responseAsJson();
  }

  private void handleStopNode(String memberAddress) {
    log().info("Stop node {}", memberAddress);
    final var cluster = Cluster.get(actorSystem);
    cluster.state().getMembers().forEach(member -> {
      if (memberAddress.equals(member.address().toString())) {
        cluster.manager().tell(Leave.create(member.address()));
      }
    });
  }

  private Message responseAsJson() {
    clearInactiveNodes();
    tree.setMemberType(Cluster.get(actorSystem).selfMember().address().toString(), "httpServer");
    final var clientResponse = new ClientResponse(tree, activitySummary);
    return TextMessage.create(clientResponse.toJson());
  }

  private void clearInactiveNodes() {
    final var cluster = Cluster.get(actorSystem);
    final var clusterState = cluster.state();

    final var unreachable = clusterState.getUnreachable();

    final var members = StreamSupport.stream(clusterState.getMembers().spliterator(), false)
        .filter(member -> member.status().equals(MemberStatus.up()))
        .filter(member -> !(unreachable.contains(member)))
        .collect(Collectors.toList());
    final var memberIps = members.stream().map(m -> m.address().getHost().orElse("0.0.0.0")).collect(Collectors.toList());
    final var memberAddresses = members.stream().map(m -> m.address().toString()).collect(Collectors.toList());

    clearInactiveNodesFromTree(memberAddresses);
    clearInactiveNodesFromActivitySummary(memberIps);
  }

  private void clearInactiveNodesFromTree(List<String> members) {
    final var count = tree.children.size();
    tree.children.removeIf(node -> !members.contains(node.name));
    if (count != tree.children.size()) {
      log().info("Removed {} members from tree", count - tree.children.size());
    }
  }

  private static final long activityIdleLimitNs = 15 * 1000 * 1000000L; // Ns in ns = 10 s * 1,000 ms/s * 1,000,000 ns/ms

  private void clearInactiveNodesFromActivitySummary(List<String> members) {
    activitySummary.clientActivitySummary.clientActivities.entrySet().removeIf(c -> System.nanoTime() - c.getValue().lastAccessedNs > activityIdleLimitNs);
    activitySummary.serverActivitySummary.serverActivities.entrySet().removeIf(s -> !members.contains(s.getValue().server.ip));
  }

  void load(EntityAction entityAction) {
    switch (entityAction.action) {
      case "start":
        tree.add(entityAction.member, entityAction.shardId, entityAction.entityId);
        activitySummary.load(entityAction);
        break;
      case "ping":
        activitySummary.load(entityAction);
        break;
      case "stop":
        tree.remove(entityAction.member, entityAction.shardId, entityAction.entityId);
        break;
    }
  }

  public interface Statistics extends CborSerializable {}

  public static class EntityAction implements Statistics {
    final String member;
    final String shardId;
    final String entityId;
    final String action;
    final IpId.Client httpClient;
    final IpId.Server httpServer;

    @JsonCreator
    EntityAction(String member, String shardId, String entityId, String action, IpId.Client httpClient, IpId.Server httpServer) {
      this.member = member;
      this.shardId = shardId;
      this.entityId = entityId;
      this.action = action;
      this.httpClient = httpClient;
      this.httpServer = httpServer;
    }

    @Override
    public String toString() {
      return String.format("%s[%s, %s, %s, %s, %s, %s]", getClass().getSimpleName(), member, shardId, entityId, action, httpClient, httpServer);
    }
  }

  public static class Tree implements Serializable {
    private static final long serialVersionUID = 1L;
    public final String name;
    public String type;
    public int events;
    public final List<Tree> children = new ArrayList<>();

    public Tree(String name, String type) {
      this.name = name;
      this.type = type;
    }

    static Tree create(String name, String type) {
      return new Tree(name, type);
    }

    Tree children(Tree... children) {
      this.children.addAll(Arrays.asList(children));
      return this;
    }

    void add(String memberId, String shardId, String entityId) {
      removeEntity(entityId);
      var member = find(memberId, "member");
      if (member == null) {
        member = Tree.create(memberId, "member");
        children.add(member);
      }
      var shard = member.find(shardId, "shard");
      if (shard == null) {
        shard = Tree.create(shardId, "shard");
        member.children.add(shard);
      }
      var entity = shard.find(entityId, "entity");
      if (entity == null) {
        entity = Tree.create(entityId, "entity");
        shard.children.add(entity);
      }
    }

    void remove(String memberId, String shardId, String entityId) {
      var member = find(memberId, "member");
      if (member != null) {
        var shard = member.find(shardId, "shard");
        if (shard != null) {
          var entity = shard.find(entityId, "entity");
          shard.children.remove(entity);

          if (shard.children.isEmpty()) {
            member.children.remove(shard);
          }
        }
        if (member.children.isEmpty()) {
          children.remove(member);
        }
      }
    }

    void removeEntity(String entityId) {
      for (var member : children) {
        for (var shard : member.children) {
          for (var entity : shard.children) {
            if (entity.name.equals(entityId)) {
              shard.children.remove(entity);
              break;
            }
          }
        }
      }
    }

    void incrementEvents(String memberId, String shardId, String entityId) {
      final var entity = find(memberId, shardId, entityId);
      if (entity != null) {
        entity.events += 1;
      }
    }

    private Tree find(String memberId, String shardId, String entityId) {
      final var member = find(memberId, "member");
      if (member != null) {
        final var shard = member.find(shardId, "shard");
        if (shard != null) {
          final var entity = shard.find(entityId, "entity");
          if (entity != null) {
            return entity;
          }
        }
      }
      return null;
    }

    Tree find(String name, String type) {
      if (this.name.equals(name) && this.type.contains(type)) {
        return this;
      } else {
        for (var child : children) {
          final var found = child.find(name, type);
          if (found != null) {
            return found;
          }
        }
      }
      return null;
    }

    void setMemberType(String memberId, String type) {
      children.forEach(child -> {
        if (child.name.equals(memberId)) {
          if (!child.type.contains(type)) {
            child.type = child.type + " " + type;
          }
        } else if (child.type.contains(type)) {
          unsetMemberType(child.name, type);
        }
      });
    }

    void unsetMemberType(String memberId, String type) {
      final var member = find(memberId, type);
      if (member != null) {
        member.type = member.type.replaceAll(type, "");
        member.type = member.type.replaceAll(" +", " ");
      }
    }

    int leafCount() {
      if (children.size() > 0) {
        return children.stream().mapToInt(Tree::leafCount).sum();
      } else {
        return 1;
      }
    }

    int eventsCount() {
      if (children.size() > 0) {
        return children.stream().mapToInt(Tree::eventsCount).sum();
      } else {
        return events;
      }
    }

    @Override
    public String toString() {
      return String.format("%s[%s, %s, %d]", getClass().getSimpleName(), name, type, events);
    }
  }

  public static class ActivitySummary implements Serializable {
    private static final long serialVersionUID = 1L;
    public final ClientActivitySummary clientActivitySummary = new ClientActivitySummary();
    public final ServerActivitySummary serverActivitySummary = new ServerActivitySummary();

    void load(EntityAction entityAction) {
      clientActivitySummary.load(entityAction);
      serverActivitySummary.load(entityAction);
    }

    @Override
    public String toString() {
      return String.format("%s[%s, %s]", getClass().getSimpleName(), 
        clientActivitySummary.clientActivities.values(), serverActivitySummary.serverActivities.values());
    }
  }

  public static class ClientActivitySummary implements Serializable {
    private static final long serialVersionUID = 1L;
    public final Map<IpId.Client, ClientActivity> clientActivities = new HashMap<>();

    void load(EntityAction entityAction) {
      final var client = entityAction.httpClient;
      clientActivities.put(client, clientActivities.getOrDefault(client, new ClientActivity(client)).load(entityAction));
    }

    Collection<ClientActivity> activityOLD() {
      return clientActivities.values();
    }

    @Override
    public String toString() {
      return String.format("%s[%s]", getClass().getSimpleName(), clientActivities);
    }

    public static class ClientActivity implements Serializable {
      private static final long serialVersionUID = 1L;
      public final IpId.Client client;
      public int messageCount;
      public long lastAccessedNs;
      public Queue<Link> links = new LinkedList<>();

      public ClientActivity(Client client) {
        this.client = client;
        messageCount = 0;
        lastAccessedNs = 0;
      }

      ClientActivity load(EntityAction entityAction) {
        messageCount++;
        lastAccessedNs = System.nanoTime();

        links.offer(new Link(entityAction.entityId, entityAction.httpClient, entityAction.httpServer));
        while (links.size() > 50) {
          links.poll();
        }
        return this;
      }

      @Override
      public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((client == null) ? 0 : client.hashCode());
        return result;
      }

      @Override
      public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null) return false;
        if (getClass() != obj.getClass()) return false;
        ClientActivity other = (ClientActivity) obj;
        if (client == null) {
          if (other.client != null) return false;
        } else if (!client.equals(other.client)) return false;
        return true;
      }

      @Override
      public String toString() {
        return String.format("%s[%s, %,d, %,dns]", getClass().getSimpleName(), client, messageCount, lastAccessedNs);
      }
    }
  }

  public static class ServerActivitySummary implements Serializable {
    private static final long serialVersionUID = 1L;
    public final Map<IpId.Server, ServerActivity> serverActivities = new HashMap<>();

    void load(EntityAction entityAction) {
      final var server = entityAction.httpServer;
      serverActivities.put(server, serverActivities.getOrDefault(server, new ServerActivity(server)).load(entityAction));
    }

    @Override
    public String toString() {
      return String.format("%s[%s]", getClass().getSimpleName(), serverActivities);
    }

    public static class ServerActivity implements Serializable {
      private static final long serialVersionUID = 1L;
      public final IpId.Server server;
      public int messageCount;
      public Queue<Link> links = new LinkedList<>();

      public ServerActivity(IpId.Server server) {
        this.server = server;
        messageCount = 0;
      }

      ServerActivity load(EntityAction entityAction) {
        messageCount++;

        links.offer(new Link(entityAction.entityId, entityAction.httpClient, entityAction.httpServer));
        while (links.size() > 50) {
          links.poll();
        }
        return this;
      }

      @Override
      public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((server == null) ? 0 : server.hashCode());
        return result;
      }

      @Override
      public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null) return false;
        if (getClass() != obj.getClass()) return false;
        ServerActivity other = (ServerActivity) obj;
        if (server == null) {
          if (other.server != null) return false;
        } else if (!server.equals(other.server)) return false;
        return true;
      }

      @Override
      public String toString() {
        return String.format("%s[%s, %,d]", getClass().getSimpleName(), server, messageCount);
      }
    }
  }

  public static class Link implements Serializable {
    private static final long serialVersionUID = 1L;
    public final String entityId;
    public final IpId.Client client;
    public final IpId.Server server;

    public Link(String entityId, Client client, Server server) {
      this.entityId = entityId;
      this.client = client;
      this.server = server;
    }

    @Override
    public String toString() {
      return String.format("%s[%s, %s, %s]", getClass().getSimpleName(), entityId, client, server);
    }
  }

  public static class ClientResponse implements Serializable {
    private static final long serialVersionUID = 1L;
    public final Tree tree;
    public final Collection<ClientActivity> clientActivities;
    public final Collection<ServerActivity> serverActivities;

    public ClientResponse(Tree tree, ActivitySummary activitySummary) {
      this.tree = tree;
      clientActivities = activitySummary.clientActivitySummary.clientActivities.values();
      serverActivities = activitySummary.serverActivitySummary.serverActivities.values();
    }

    String toJson() {
      final var ow = new ObjectMapper().writer().withDefaultPrettyPrinter();
      try {
        return ow.writeValueAsString(this);
      } catch (JsonProcessingException e) {
        return String.format("{ \"error\" : \"%s\" }", e.getMessage());
      }
    }
  }

  private Logger log() {
    return actorSystem.log();
  }
}
