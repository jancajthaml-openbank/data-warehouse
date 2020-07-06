package com.openbank.dwh.boot

import akka.Done
import akka.actor.typed.ActorSystem
import com.typesafe.scalalogging.LazyLogging
import scala.concurrent.{Future, Promise}
import com.openbank.dwh.actor


trait TypedActorModule extends Lifecycle {
  self: AkkaModule with ServiceModule with LazyLogging =>

  private var typedSystem: ActorSystem[actor.GuardianActor.Command] = null

  // FIXME need separate batch actor that will update daily balance changes on accounts based on
  // new transactions and will run in a loop
  abstract override def setup(): Future[Done] = {
    super.setup().flatMap { _ =>
      logger.info("Starting Typed Actor Module")
      typedSystem = ActorSystem(
        actor.GuardianActor(primaryDataExplorationService),
        actor.GuardianActor.namespace
      )
      Future.successful(Done)
    }
  }

  abstract override def stop(): Future[Done] = {
    Future.successful(Done)
      .flatMap {
        case _ if typedSystem != null =>
          val wait = Promise[Done]()
          typedSystem ! actor.GuardianActor.ShutdownActors(wait)
          wait.future
        case _ =>
          Future.successful(Done)
      }
      .flatMap(_ => super.stop())
  }

  abstract override def start(): Future[Done] = {
    if (typedSystem != null) {
      typedSystem ! actor.GuardianActor.StartActors
    }
    super.start()
  }

}

