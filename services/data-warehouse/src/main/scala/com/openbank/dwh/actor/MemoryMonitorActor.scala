package com.openbank.dwh.actor

import akka.Done
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{Behavior, SupervisorStrategy}
import com.typesafe.scalalogging.StrictLogging
import scala.concurrent.duration._
import com.openbank.dwh.metrics.StatsDClient

object MemoryMonitor {

  val name = "memory-monitor"

  case object ReportMemoryStats extends Guardian.Command

}

object MemoryMonitorActor extends StrictLogging {

  import MemoryMonitor._

  case class BehaviorProps(metrics: StatsDClient)

  private lazy val delay = 1.seconds

  def apply(metrics: StatsDClient) = {
    val props = BehaviorProps(metrics)

    Behaviors
      .supervise {
        Behaviors.withTimers[Guardian.Command] { timer =>
          timer.startTimerAtFixedRate(ReportMemoryStats, delay)
          active(props)
        }
      }
      .onFailure[Exception](
        SupervisorStrategy.restart.withLimit(Int.MaxValue, delay)
      )
  }

  def active(props: BehaviorProps): Behavior[Guardian.Command] =
    Behaviors.receive {

      case (_, ReportMemoryStats) =>
        logger.debug("active(ReportMemoryStats)")

        val runtime = Runtime.getRuntime

        props.metrics.gauge(
          "memory.bytes",
          (runtime.totalMemory - runtime.freeMemory)
        )

        Behaviors.same

      case (_, Guardian.Shutdown(replyTo)) =>
        logger.debug("active(Shutdown)")
        Behaviors.stopped { () =>
          replyTo ! Done
        }

      case (_, msg) =>
        logger.debug(s"active(${msg})")
        Behaviors.unhandled

    }

}
