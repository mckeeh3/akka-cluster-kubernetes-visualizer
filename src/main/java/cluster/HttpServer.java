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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import org.slf4j.Logger;
import akka.NotUsed;
import akka.actor.typed.ActorSystem;
import akka.cluster.ClusterEvent;
import akka.cluster.Member;
import akka.cluster.MemberStatus;
import akka.cluster.sharding.typed.javadsl.ClusterSharding;
import akka.cluster.sharding.typed.javadsl.EntityRef;
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
import cluster.IpId.Client;

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
    String entityId = changeValue.id.id;
    EntityRef<EntityActor.Command> entityRef = clusterSharding.entityRefFor(EntityActor.entityTypeKey, entityId);
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
                    final GetValueAck ack = new GetValueAck(id.id, getValueAck.value.value, getValue.nsStart, "success", StatusCodes.ACCEPTED.intValue());
                    return complete(StatusCodes.ACCEPTED, ack, Jackson.marshaller());
                  });
            }
        )
    );
  }

  private CompletionStage<EntityActor.GetValueAck> submitGetValue(EntityActor.GetValue getValue) {
    String entityId = getValue.id.id;
    EntityRef<EntityActor.Command> entityRef = clusterSharding.entityRefFor(EntityActor.entityTypeKey, entityId);
    return entityRef.ask(getValue::replyTo, Duration.ofSeconds(30))
        .handle((reply, e) -> {
          if (reply != null) {
            return new EntityActor.GetValueAck(getValue.id, new Value(reply instanceof EntityActor.GetValueAck
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
    String messageText = message.asTextMessage().getStrictText();
    if (messageText.startsWith("akka://")) {
      handleStopNode(messageText);
    }
    log().info("{}", activitySummary);
    return getTreeAsJson();
  }

  private void handleStopNode(String memberAddress) {
    log().info("Stop node {}", memberAddress);
    final Cluster cluster = Cluster.get(actorSystem);
    cluster.state().getMembers().forEach(member -> {
      if (memberAddress.equals(member.address().toString())) {
        cluster.manager().tell(Leave.create(member.address()));
      }
    });
  }

  private Message getTreeAsJson() {
    clearInactiveNodesFromTree();
    tree.setMemberType(Cluster.get(actorSystem).selfMember().address().toString(), "httpServer");
    return TextMessage.create(tree.toJson());
  }

  private void clearInactiveNodesFromTree() {
    final Cluster cluster = Cluster.get(actorSystem);
    final ClusterEvent.CurrentClusterState clusterState = cluster.state();

    final Set<Member> unreachable = clusterState.getUnreachable();

    List<String> members = StreamSupport.stream(clusterState.getMembers().spliterator(), false)
        .filter(member -> member.status().equals(MemberStatus.up()))
        .filter(member -> !(unreachable.contains(member)))
        .map(member -> member.address().toString()).collect(Collectors.toList());

    final int count = tree.children.size();
    tree.children.removeIf(node -> !members.contains(node.name));
    if (count != tree.children.size()) {
      log().info("Removed {} members from tree", count - tree.children.size());
    }
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
      Tree member = find(memberId, "member");
      if (member == null) {
        member = Tree.create(memberId, "member");
        children.add(member);
      }
      Tree shard = member.find(shardId, "shard");
      if (shard == null) {
        shard = Tree.create(shardId, "shard");
        member.children.add(shard);
      }
      Tree entity = shard.find(entityId, "entity");
      if (entity == null) {
        entity = Tree.create(entityId, "entity");
        shard.children.add(entity);
      }
    }

    void remove(String memberId, String shardId, String entityId) {
      Tree member = find(memberId, "member");
      if (member != null) {
        Tree shard = member.find(shardId, "shard");
        if (shard != null) {
          Tree entity = shard.find(entityId, "entity");
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
      for (Tree member : children) {
        for (Tree shard : member.children) {
          for (Tree entity : shard.children) {
            if (entity.name.equals(entityId)) {
              shard.children.remove(entity);
              break;
            }
          }
        }
      }
    }

    void incrementEvents(String memberId, String shardId, String entityId) {
      Tree entity = find(memberId, shardId, entityId);
      if (entity != null) {
        entity.events += 1;
      }
    }

    private Tree find(String memberId, String shardId, String entityId) {
      Tree member = find(memberId, "member");
      if (member != null) {
        Tree shard = member.find(shardId, "shard");
        if (shard != null) {
          Tree entity = shard.find(entityId, "entity");
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
        for (Tree child : children) {
          Tree found = child.find(name, type);
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
      Tree member = find(memberId, type);
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

    String toJson() {
      ObjectWriter ow = new ObjectMapper().writer().withDefaultPrettyPrinter();
      try {
        return ow.writeValueAsString(this);
      } catch (JsonProcessingException e) {
        return String.format("{ \"error\" : \"%s\" }", e.getMessage());
      }
    }

    @Override
    public String toString() {
      return String.format("%s[%s, %s, %d]", getClass().getSimpleName(), name, type, events);
    }
  }

  private static final long activityIdleLimitNs = 10 * 1000 * 1000000; // 10s in ns = 10 s * 1,000 ms/s  * 1,000,000 ns/ms

  public static class ActivitySummary implements Serializable {
    private static final long serialVersionUID = 1L;
    final ClientActivitySummary clientActivitySummary = new ClientActivitySummary();
    final ServerActivitySummary serverActivitySummary = new ServerActivitySummary();

    void load(EntityAction entityAction) {
      clientActivitySummary.load(entityAction.httpClient);
      serverActivitySummary.load(entityAction.httpServer);
    }

    @Override
    public String toString() {
      return String.format("%s[%s, %s]", getClass().getSimpleName(), clientActivitySummary.activity(), serverActivitySummary.activity());
    }
  }

  public static class ClientActivitySummary implements Serializable {
    private static final long serialVersionUID = 1L;
    private final Map<IpId.Client, ClientActivity> clientActivities = new HashMap<>();

    void load(IpId.Client client) {
      clientActivities.put(client, clientActivities.getOrDefault(client, new ClientActivity(client)).increment());
    }

    Collection<ClientActivity> activity() {
      clientActivities.entrySet().removeIf(c -> System.nanoTime() - c.getValue().lastAccessed > activityIdleLimitNs);
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
      public long lastAccessed;

      public ClientActivity(Client client) {
        this.client = client;
        messageCount = 0;
        lastAccessed = 0;
      }

      ClientActivity increment() {
        messageCount++;
        lastAccessed = System.nanoTime();
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
        return String.format("%s[%s, %,d, %,dns]", getClass().getSimpleName(), client, messageCount, lastAccessed);
      }
    }
  }

  public static class ServerActivitySummary implements Serializable {
    private static final long serialVersionUID = 1L;
    private Map<IpId.Server, ServerActivity> serverActivities = new HashMap<>();

    void load(IpId.Server server) {
      serverActivities.put(server, serverActivities.getOrDefault(server, new ServerActivity(server)).increment());
    }

    Collection<ServerActivity> activity() {
      serverActivities.entrySet().removeIf(s -> System.nanoTime() - s.getValue().lastAccessed > activityIdleLimitNs);
      return serverActivities.values();
    }

    @Override
    public String toString() {
      return String.format("%s[%s]", getClass().getSimpleName(), serverActivities);
    }

    public static class ServerActivity implements Serializable {
      private static final long serialVersionUID = 1L;
      public final IpId.Server server;
      public int messageCount;
      public long lastAccessed;

      public ServerActivity(IpId.Server server) {
        this.server = server;
        messageCount = 0;
        lastAccessed = 0;
      }

      ServerActivity increment() {
        messageCount++;
        lastAccessed = System.nanoTime();
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
        return String.format("%s[%s, %,d, %,dns]", getClass().getSimpleName(), server, messageCount, lastAccessed);
      }
    }
  }

  private Logger log() {
    return actorSystem.log();
  }
}
