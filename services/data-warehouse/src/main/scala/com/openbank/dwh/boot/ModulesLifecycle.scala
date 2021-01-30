package com.openbank.dwh.boot

import scala.util.Try
import akka.Done
import akka.actor.CoordinatedShutdown
import akka.actor.CoordinatedShutdown.Reason
import ch.qos.logback.classic.LoggerContext
import com.typesafe.scalalogging.StrictLogging
import org.slf4j.LoggerFactory
import scala.concurrent.Future

trait ModulesLifecycle extends Lifecycle {
  self: AkkaModule with StrictLogging =>

  abstract override def setup(): Future[Done] = {
    super.setup().map { _ =>
      CoordinatedShutdown(system)
        .addTask(
          CoordinatedShutdown.PhaseBeforeActorSystemTerminate,
          "graceful-stop"
        ) { () =>
          stop()
        }
      Done
    }
  }

  abstract override def stop(): Future[Done] = {
    Future
      .fromTry(Try {
        LoggerFactory.getILoggerFactory match {
          case c: LoggerContext => c.stop()
          case _ => ()
        }
      })
      .flatMap(_ => super.stop())
  }

  def kill(): Future[Done] = CoordinatedShutdown(system).run(StartupFailedReason)
  def shutdown(): Future[Done] = CoordinatedShutdown(system).run(ShutDownReason)

  private object StartupFailedReason extends Reason
  private object ShutDownReason extends Reason
}
