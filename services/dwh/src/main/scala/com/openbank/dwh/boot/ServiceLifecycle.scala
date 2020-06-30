package com.openbank.dwh.boot

import akka.Done
import akka.actor.CoordinatedShutdown
import akka.actor.CoordinatedShutdown.Reason
import ch.qos.logback.classic.LoggerContext
import com.typesafe.scalalogging.StrictLogging
import org.slf4j.LoggerFactory
import scala.concurrent.Future


trait ServiceLifecycle extends Lifecycle {
  self: AkkaModule with StrictLogging =>

  abstract override def start(): Future[Done] = {
    super.start().flatMap { _ =>
      CoordinatedShutdown(system)
        .addTask(CoordinatedShutdown.PhaseBeforeActorSystemTerminate, "service-cleanup") { () =>
          stop()
        }
      Future.successful(Done)
    }
  }

  abstract override def stop(): Future[Done] = {
    LoggerFactory.getILoggerFactory match {
      case c: LoggerContext => c.stop()
    }
    super.stop()
  }

  def kill(): Future[Done] = CoordinatedShutdown(system).run(StartupFailedReason)
  def shutdown(): Future[Done] = CoordinatedShutdown(system).run(ShutDownReason)

  private object StartupFailedReason extends Reason
  private object ShutDownReason extends Reason
}
