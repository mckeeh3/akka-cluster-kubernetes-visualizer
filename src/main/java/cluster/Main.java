package cluster;

import akka.actor.typed.ActorRef;
import akka.actor.typed.ActorSystem;
import akka.actor.typed.Behavior;
import akka.actor.typed.Terminated;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.cluster.sharding.typed.javadsl.ClusterSharding;
import akka.cluster.sharding.typed.javadsl.Entity;
import akka.management.cluster.bootstrap.ClusterBootstrap;
import akka.management.javadsl.AkkaManagement;
import cluster.HttpServer.Statistics;

class Main {

  static Behavior<Void> create() {
    return Behaviors.setup(context -> {
          bootstrap(context);

          return Behaviors.receive(Void.class)
            .onSignal(Terminated.class, signal -> Behaviors.stopped())
            .build();
        });
  }

  private static void bootstrap(final ActorContext<Void> context) {
    context.spawn(ClusterListenerActor.create(), ClusterListenerActor.class.getSimpleName());
    ActorRef<Statistics> httpServerActorRef = context.spawn(HttpServerActor.create(), HttpServerActor.class.getSimpleName());

    startClusterSharding(context.getSystem(), httpServerActorRef);
  }

  public static void main(String[] args) {
    final ActorSystem<?> actorSystem = ActorSystem.create(Main.create(), "cluster");
    startClusterBootstrap(actorSystem);
  }

  private static void startClusterBootstrap(ActorSystem<?> actorSystem) {
    AkkaManagement.get(actorSystem).start();
    ClusterBootstrap.get(actorSystem).start();
  }

  private static void startClusterSharding(final ActorSystem<?> actorSystem, ActorRef<HttpServer.Statistics> httpServerActorRef) {
    final ClusterSharding clusterSharding = ClusterSharding.get(actorSystem);
    clusterSharding.init(
      Entity.of(
        EntityActor.entityTypeKey,
        entityContext ->
          EntityActor.create(entityContext.getEntityId(), httpServerActorRef)
      )
      .withStopMessage(EntityActor.Passivate.INSTANCE)
    );
  }
}
