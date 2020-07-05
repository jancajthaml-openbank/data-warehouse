package com.openbank.dwh.boot

import akka.Done
import akka.actor.typed.ActorSystem
import com.typesafe.scalalogging.LazyLogging
import scala.concurrent.Future
import com.openbank.dwh.actor


trait TypedActorModule extends Lifecycle {
  self: AkkaModule with ServiceModule with LazyLogging =>

  private var typedSystem: ActorSystem[actor.GuardianActor.Command] = null

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

  abstract override def start(): Future[Done] = {
    typedSystem ! actor.GuardianActor.StartActors
    super.start()
  }

}

