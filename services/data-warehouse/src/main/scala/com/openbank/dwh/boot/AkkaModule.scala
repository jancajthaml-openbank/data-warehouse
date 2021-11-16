package com.openbank.dwh.boot

import akka.Done
import akka.actor.CoordinatedShutdown
import com.typesafe.scalalogging.StrictLogging
import com.openbank.dwh.actor.{Guardian, GuardianActor}
import scala.concurrent.{ExecutionContext, Future}
import akka.stream.{Materializer, SystemMaterializer}

trait AkkaModule {

  def untypedSystem: akka.actor.ActorSystem

  def typedSystem: akka.actor.typed.ActorSystem[Guardian.Command]

  implicit def executionContext: ExecutionContext

  implicit def materializer: Materializer

}

trait ProductionAkkaModule extends AkkaModule with Lifecycle {
  self: ConfigModule with StrictLogging =>

  lazy val typedSystem: akka.actor.typed.ActorSystem[Guardian.Command] =
    akka.actor.typed.ActorSystem(GuardianActor(), Guardian.name)

  lazy val untypedSystem: akka.actor.ActorSystem = typedSystem.classicSystem

  implicit lazy val executionContext: ExecutionContext = typedSystem.executionContext

  implicit lazy val materializer: Materializer = SystemMaterializer(untypedSystem).materializer

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
          )(() => stop())
        Done
      }
  }

  abstract override def stop(): Future[Done] = {
    logger.info("Stopping Akka Module")
    super.stop()
  }

  def shutdown(): Future[Done] =
    CoordinatedShutdown(typedSystem).run(CoordinatedShutdown.JvmExitReason)

}
