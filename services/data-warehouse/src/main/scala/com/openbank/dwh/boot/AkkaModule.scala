package com.openbank.dwh.boot

import akka.Done
import akka.actor.CoordinatedShutdown.{JvmExitReason, Reason}
import akka.actor.{ActorSystem, CoordinatedShutdown, Scheduler}
import com.typesafe.scalalogging.StrictLogging

import scala.concurrent.{ExecutionContext, Future}

trait AkkaModule {

  implicit def system: ActorSystem

  implicit def scheduler: Scheduler

  implicit def executionContext: ExecutionContext

}

trait ProductionAkkaModule extends AkkaModule with Lifecycle {
  self: ConfigModule with TypedActorModule with StrictLogging =>

  implicit def system: ActorSystem = typedSystem.classicSystem

  implicit lazy val scheduler: Scheduler = system.scheduler

  implicit lazy val executionContext: ExecutionContext = typedSystem.executionContext

  abstract override def start(): Future[Done] = {
    super.start().map { _ =>
      logger.info("Starting Akka Module")
      Done
    }
  }
  abstract override def setup(): Future[Done] = {
    super
      .setup()
      .map { _ =>
        CoordinatedShutdown(typedSystem)
          .addTask(
            CoordinatedShutdown.PhaseBeforeActorSystemTerminate,
            "graceful-stop"
          )(stop)
        Done
      }(executionContext)
  }

  abstract override def stop(): Future[Done] = {
    logger.info("Stopping Akka Module")
    super.stop()
  }

  def shutdown(): Future[Done] =
    CoordinatedShutdown(typedSystem).run(JvmExitReason)

}
