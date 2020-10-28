package cluster;

import akka.actor.typed.ActorSystem;
import akka.actor.typed.Behavior;
import akka.actor.typed.Terminated;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.management.cluster.bootstrap.ClusterBootstrap;
import akka.management.javadsl.AkkaManagement;

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
    context.spawn(ClusterListenerActor.create(), "clusterListener");
    context.spawn(HttpServerActor.create(), "httpServerActor");
  }

  public static void main(String[] args) {
    final ActorSystem<?> actorSystem = ActorSystem.create(Main.create(), "cluster");
    startClusterBootstrap(actorSystem);
  }

  private static void startClusterBootstrap(ActorSystem<?> actorSystem) {
    AkkaManagement.get(actorSystem).start();
    ClusterBootstrap.get(actorSystem).start();
  }
}
