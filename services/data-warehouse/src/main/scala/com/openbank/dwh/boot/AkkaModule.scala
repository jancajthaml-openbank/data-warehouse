package com.openbank.dwh.boot

import akka.Done
import akka.actor.CoordinatedShutdown.Reason
import akka.actor.{Scheduler, ActorSystem, CoordinatedShutdown}
import ch.qos.logback.classic.LoggerContext
import com.typesafe.scalalogging.StrictLogging
import org.slf4j.LoggerFactory
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

trait AkkaModule {

  implicit def system: ActorSystem

  implicit def scheduler: Scheduler

  implicit def executionContext: ExecutionContext

}

trait ProductionAkkaModule extends AkkaModule with Lifecycle {
  self: ConfigModule with TypedActorModule with StrictLogging =>

  implicit def system: ActorSystem = typedSystem.classicSystem

  implicit lazy val scheduler: Scheduler = system.scheduler

  implicit def executionContext: ExecutionContext =
    typedSystem.executionContext

  abstract override def setup(): Future[Done] = {
    super
      .setup()
      .map { _ =>
        CoordinatedShutdown(typedSystem)
          .addTask(
            CoordinatedShutdown.PhaseBeforeActorSystemTerminate,
            "graceful-stop"
          ) { () =>
            stop()
          }
        Done
      }(typedSystem.executionContext)
  }

  abstract override def stop(): Future[Done] = {
    Future
      .fromTry(Try {
        LoggerFactory.getILoggerFactory match {
          case c: LoggerContext => c.stop()
          case _                => ()
        }
      })
      .flatMap(_ => super.stop())(typedSystem.executionContext)
  }

  def kill(): Future[Done] =
    CoordinatedShutdown(typedSystem).run(StartupFailedReason)

  def shutdown(): Future[Done] =
    CoordinatedShutdown(typedSystem).run(ShutDownReason)

  private object StartupFailedReason extends Reason

  private object ShutDownReason extends Reason
}
