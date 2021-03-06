package cluster;

import com.fasterxml.jackson.annotation.JsonCreator;

import org.slf4j.Logger;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Receive;
import akka.actor.typed.javadsl.Behaviors;
import akka.cluster.sharding.typed.javadsl.EntityTypeKey;
import cluster.HttpServer.EntityAction;
import cluster.HttpServerActor.BroadcastEntityAction;

public class EntityActor extends AbstractBehavior<EntityActor.Command> {
  private final ActorContext<Command> actorContext;
  private final String entityId;
  private final String shardId;
  private final String memberId;
  private final ActorRef<HttpServer.Statistics> httpServerActorRef;
  private State state;
  static EntityTypeKey<Command> entityTypeKey = EntityTypeKey.create(Command.class, EntityActor.class.getSimpleName());

  static Behavior<Command> create(String entityId, ActorRef<HttpServer.Statistics> httpServerActorRef) {
    return Behaviors.setup(actorContext -> new EntityActor(actorContext, entityId, httpServerActorRef));
  }

  private EntityActor(ActorContext<Command> actorContext, String entityId, ActorRef<HttpServer.Statistics> httpServerActorRef) {
    super(actorContext);
    this.actorContext = actorContext;
    this.entityId = entityId;
    this.httpServerActorRef = httpServerActorRef;
    shardId = "" + Math.abs(entityId.hashCode()) % actorContext.getSystem().settings().config().getInt("akka.cluster.sharding.number-of-shards");
    memberId = actorContext.getSystem().address().toString();
    log().info("Start {}", entityId);
  }

  @Override
  public Receive<Command> createReceive() {
    return newReceiveBuilder()
      .onMessage(ChangeValue.class, this::onChangeValue)
      .onMessage(GetValue.class, this::onGetValue)
      .onMessage(Passivate.class, msg -> onPassivate())
      .build();
  }

  private Behavior<Command> onChangeValue(ChangeValue changeValue) {
    if (state == null) {
      state = new State(changeValue.id, changeValue.value);
      log().info("initialize {}", state);

      changeValue.replyTo.tell(new ChangeValueAck("initialize", changeValue.id, changeValue.value));
      notifyHttpServer("start", changeValue.httpClient, changeValue.httpServer);
    } else {
      log().info("update {} {} -> {}", state.id, state.value, changeValue.value);
      state.value = changeValue.value;
      changeValue.replyTo.tell(new ChangeValueAck("update", changeValue.id, changeValue.value));
      notifyHttpServer("ping", changeValue.httpClient, changeValue.httpServer);
    }
    return this;
  }

  private Behavior<Command> onGetValue(GetValue getValue) {
    log().info("{} -> {}", getValue, state == null ? "(not initialized)" : state);
    if (state == null) {
      getValue.replyTo.tell(new GetValueAckNotFound(getValue.id));
      state = new State(getValue.id, new Value(""));
      notifyHttpServer("start", getValue.httpClient, getValue.httpServer);
    } else {
      getValue.replyTo.tell(new GetValueAck(state.id, state.value));
      notifyHttpServer("ping", getValue.httpClient, getValue.httpServer);
    }
    return this;
  }

  private Behavior<Command> onPassivate() {
    log().info("Stop passivate {}", entityId);
    notifyHttpServer("stop", null, null);
    return Behaviors.stopped();
  }

  private void notifyHttpServer(String action, IpId.Client httpClient, IpId.Server httpServer) {
    final var entityAction = new EntityAction(memberId, shardId, entityId, action, httpClient, httpServer);
    final var broadcastEntityAction = new BroadcastEntityAction(entityAction);
    httpServerActorRef.tell(broadcastEntityAction);
  }

  private Logger log() {
    return actorContext.getSystem().log();
  }

  static String entityId(int nodePort, int id) {
    return String.format("%d-%d", nodePort, id);
  }

  public interface Command extends CborSerializable {}

  public static class ChangeValue implements Command {
    public final Id id;
    public final Value value;
    public final ActorRef<Command> replyTo;
    public final IpId.Client httpClient;
    public final IpId.Server httpServer;

    @JsonCreator
    public ChangeValue(Id id, Value value, ActorRef<Command> replyTo, IpId.Client httpClient, IpId.Server httpServer) {
      this.id = id;
      this.value = value;
      this.replyTo = replyTo;
      this.httpClient = httpClient;
      this.httpServer = httpServer;
    }

    ChangeValue replyTo(ActorRef<Command> replyTo) {
      return new ChangeValue(id, value, replyTo, httpClient, httpServer);
    }

    @Override
    public String toString() {
      return String.format("%s[%s, %s, %s, %s]", getClass().getSimpleName(), id, value, httpClient, httpServer);
    }
  }

  public static class ChangeValueAck implements Command {
    public final String action;
    public final Id id;
    public final Value value;

    @JsonCreator
    public ChangeValueAck(String action, Id id, Value value) {
      this.action = action;
      this.id = id;
      this.value = value;
    }

    @Override
    public String toString() {
      return String.format("%s[%s, %s, %s]", getClass().getSimpleName(), action, id, value);
    }
  }

  public static class GetValue implements Command {
    public final Id id;
    public final ActorRef<Command> replyTo;
    public final IpId.Client httpClient;
    public final IpId.Server httpServer;

    @JsonCreator
    public GetValue(Id id, ActorRef<Command> replyTo, IpId.Client httpClient, IpId.Server httpServer) {
      this.id = id;
      this.replyTo = replyTo;
      this.httpClient = httpClient;
      this.httpServer = httpServer;
    }

    GetValue replyTo(ActorRef<Command> replyTo) {
      return new GetValue(id, replyTo, httpClient, httpServer);
    }

    @Override
    public String toString() {
      return String.format("%s[%s, %s, %s]", getClass().getSimpleName(), id, httpClient, httpServer);
    }
  }

  public static class GetValueAck implements Command {
    public final Id id;
    public final Value value;

    @JsonCreator
    public GetValueAck(Id id, Value value) {
      this.id = id;
      this.value = value;
    }

    @Override
    public String toString() {
      return String.format("%s[%s, %s]", getClass().getSimpleName(), id, value);
    }
  }

  public static class GetValueAckNotFound implements Command {
    public final Id id;

    @JsonCreator
    public GetValueAckNotFound(Id id) {
      this.id = id;
    }

    @Override
    public String toString() {
      return String.format("%s[%s]", getClass().getSimpleName(), id);
    }
  }

  public enum Passivate implements Command {
    INSTANCE
  }

  private static class State {
    final Id id;
    Value value;

    public State(Id id, Value value) {
      this.id = id;
      this.value = value;
    }

    @Override
    public String toString() {
      return String.format("%s[%s, %s]", getClass().getSimpleName(), id, value);
    }
  }

  public static class Id implements CborSerializable {
    public final String id;

    @JsonCreator
    public Id(String id) {
      this.id = id;
    }

    @Override
    public String toString() {
      return String.format("%s[%s]", getClass().getSimpleName(), id);
    }
  }

  public static class Value implements CborSerializable {
    public final Object value;

    @JsonCreator
    public Value(Object value) {
      this.value = value;
    }

    @Override
    public String toString() {
      return String.format("%s[%s]", getClass().getSimpleName(), value);
    }
  }
}
