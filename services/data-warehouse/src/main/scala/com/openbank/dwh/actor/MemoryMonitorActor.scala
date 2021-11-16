package com.openbank.dwh.actor

import akka.Done
import akka.actor.ActorSystem.Settings
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{Behavior, SupervisorStrategy}
import com.typesafe.scalalogging.StrictLogging
import scala.concurrent.duration._
import com.openbank.dwh.metrics.StatsDClient
import akka.dispatch.{PriorityGenerator, UnboundedPriorityMailbox}
import scala.annotation.unused

object MemoryMonitor {

  val name = "memory-monitor"

  sealed trait Command extends Guardian.Command

  case object ReportMemoryStats extends Command

}

object MemoryMonitorActor {

  import MemoryMonitor._

  private lazy val delay = 1.seconds

  def apply(metrics: StatsDClient): Behavior[Guardian.Command] = {
    Behaviors
      .supervise {
        Behaviors.withTimers[Guardian.Command] { timer =>
          timer.startTimerAtFixedRate(ReportMemoryStats, delay)
          val ref = new MemoryMonitorActor(metrics)
          ref.default()
        }
      }
      .onFailure[Exception](
        SupervisorStrategy.restart.withLimit(Int.MaxValue, delay)
      )
  }

}

class MemoryMonitorActor(metrics: StatsDClient) extends StrictLogging {

  import MemoryMonitor._

  def default(): Behavior[Guardian.Command] =
    Behaviors.receive {

      case (_, ReportMemoryStats) =>
        logger.debug("active(ReportMemoryStats)")

        val runtime = Runtime.getRuntime

        metrics.gauge(
          "memory.bytes",
          runtime.totalMemory - runtime.freeMemory
        )

        Behaviors.same

      case (_, Guardian.Shutdown(replyTo)) =>
        logger.debug("active(Shutdown)")
        Behaviors.stopped { () =>
          replyTo ! Done
        }

      case (_, msg) =>
        logger.debug("active({})", msg)
        Behaviors.unhandled

    }
}

class MemoryMonitorMailbox(@unused settings: Settings, @unused config: com.typesafe.config.Config)
    extends UnboundedPriorityMailbox(
      PriorityGenerator { case _ =>
        0
      }
    )
