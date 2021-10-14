package com.openbank.dwh.boot

import akka.Done
import akka.actor.typed.ActorSystem
import akka.util.Timeout
import scala.concurrent.duration._
import akka.actor.typed.scaladsl.AskPattern._
import com.typesafe.scalalogging.StrictLogging
import scala.concurrent.Future
import com.openbank.dwh.actor.{Guardian, GuardianActor}

trait TypedActorModule extends Lifecycle {
  self: ServiceModule with MetricsModule with StrictLogging =>

  lazy val typedSystem: ActorSystem[Guardian.Command] = ActorSystem(
    GuardianActor(primaryDataExplorationService, metrics),
    Guardian.name
  )

  abstract override def setup(): Future[Done] = {
    super
      .setup()
      .flatMap { _ =>
        logger.info("Starting Guardian Actor")
        Future.successful(Done)
      }(typedSystem.executionContext)
  }

  abstract override def stop(): Future[Done] = {
    Future
      .successful(Done)
      .flatMap {
        case _ if typedSystem != null =>
          logger.info("Stopping Guardian Actor")
          typedSystem
            .ask[Done](Guardian.Shutdown)(
              Timeout(1.minutes),
              typedSystem.scheduler
            )
            .map { _ =>
              logger.info("Guardian Actor finished coordinated shutdown")
              Done
            }(typedSystem.executionContext)
        case _ =>
          Future.successful(Done)
      }(typedSystem.executionContext)
      .flatMap(_ => super.stop())(typedSystem.executionContext)
  }

  abstract override def start(): Future[Done] = {
    typedSystem ! Guardian.StartActors
    super.start()
  }

}
