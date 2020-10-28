package cluster;

import java.io.Serializable;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletionStage;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import org.slf4j.Logger;
import akka.actor.typed.ActorSystem;
import akka.cluster.sharding.typed.javadsl.ClusterSharding;
import akka.cluster.sharding.typed.javadsl.EntityRef;
import akka.http.javadsl.Http;
import akka.http.javadsl.marshallers.jackson.Jackson;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.server.Route;
import cluster.EntityActor.Value;
import cluster.EntityCommand.ChangeValueAck;
import cluster.EntityCommand.GetValueAck;
import static akka.http.javadsl.server.Directives.*;

class HttpServer {
  private final ActorSystem<?> actorSystem;
  private final ClusterSharding clusterSharding;
  private final Tree tree = new Tree("cluster", "cluster");

  static HttpServer start(String host, int port, ActorSystem<?> actorSystem) {
    return new HttpServer(host, port, actorSystem);
  } 
 
  private HttpServer(String host, int port, ActorSystem<?> actorSystem) {
    this.actorSystem = actorSystem;
    clusterSharding = ClusterSharding.get(actorSystem);

    start(host, port);
  }

  private void start(String host, int port) {
    Http.get(actorSystem).newServerAt(host, port).bind(route());
    log().info("HTTP Server started on {}:{}", host, "" + port);
  }

  private Route route() {
    return concat(
        path("entity-change", this::handleEntityChange),
        path("entity-query", this::handleEntityQuery)
    );
  }

  private Route handleEntityChange() {
    return post(
        () -> entity(
            Jackson.unmarshaller(EntityCommand.ChangeValue.class),
            changeValue -> {
              log().debug("POST {}", changeValue);
              EntityActor.Id id = new EntityActor.Id(changeValue.id);
              EntityActor.Value value = new EntityActor.Value(changeValue.value);
              return onSuccess(submitChangeValue(new EntityActor.ChangeValue(id, value, null)),
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
            log().info("change value reply {}", reply);
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
              log().debug("POST {}", getValue);
              EntityActor.Id id = new EntityActor.Id(getValue.id);
              return onSuccess(submitGetValue(new EntityActor.GetValue(id, null)),
                  getValueAck -> {
                    GetValueAck ack = new GetValueAck(id.id, getValueAck.value.value, getValue.nsStart, "TODO", StatusCodes.ACCEPTED.intValue());
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
            log().info("get value reply {}", reply);
            return new EntityActor.GetValueAck(getValue.id, new Value(reply));
          } else {
            return new EntityActor.GetValueAck(getValue.id, new Value(e.getMessage()));
          }
        });
  }

  void load(EntityAction action) {
    if ("start".equals(action.action)) {
      tree.add(action.member, action.shardId, action.entityId);
    } else if ("stop".equals(action.action)) {
      tree.remove(action.member, action.shardId, action.entityId);
    }
  }

  public interface Statistics extends CborSerializable {}

  public static class EntityAction implements Statistics {
    final String member;
    final String shardId;
    final String entityId;
    final String action;

    @JsonCreator
    EntityAction(String member, String shardId, String entityId, String action) {
      this.member = member;
      this.shardId = shardId;
      this.entityId = entityId;
      this.action = action;
    }

    @Override
    public String toString() {
      return String.format("%s[%s, %s, %s, %s]", getClass().getSimpleName(), member, shardId, entityId, action);
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
      children.forEach(
        child -> {
          if (child.name.equals(memberId)) {
            if (!child.type.contains(type)) {
              child.type = child.type + " " + type;
            }
          } else if (child.type.contains(type)) {
            unsetMemberType(child.name, type);
          }
        }
      );
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
      return String.format(
        "%s[%s, %s, %d]",
        getClass().getSimpleName(),
        name,
        type,
        events
      );
    }
  }

  private Logger log() {
    return actorSystem.log();
  }
}
