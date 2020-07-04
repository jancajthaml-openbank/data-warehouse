package com.openbank.dwh.boot

import akka.Done
import akka.actor.typed.ActorSystem
import com.typesafe.scalalogging.LazyLogging
import scala.concurrent.Future
import com.openbank.dwh.actor


trait TypedActorModule extends Lifecycle {
  self: AkkaModule with ServiceModule with LazyLogging =>

  // FIXME needs separate execution context

  abstract override def start(): Future[Done] = {
    super.start().flatMap { _ =>
      val typedSystem = ActorSystem(
        actor.GuardianActor(primaryDataExplorationService),
        actor.GuardianActor.namespace
      )

      typedSystem ! actor.GuardianActor.StartActors

      Future.successful(Done)
    }
  }

}

