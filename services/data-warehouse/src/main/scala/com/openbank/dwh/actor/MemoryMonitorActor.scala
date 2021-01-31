package com.openbank.dwh.actor

import akka.Done
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior, SupervisorStrategy}
import com.typesafe.scalalogging.StrictLogging
import scala.concurrent.duration._
import com.openbank.dwh.metrics.StatsDClient

object MemoryMonitorActor extends StrictLogging {

  val name = "memory-monitor"

  sealed trait Command extends GuardianActor.Command
  case object ReportMemoryStats extends Command
  case class Shutdown(replyTo: ActorRef[Done]) extends Command

  case class BehaviorProps(metrics: StatsDClient)

  private lazy val delay = 1.seconds

  def apply(metrics: StatsDClient) = {
    val props = BehaviorProps(metrics)

    Behaviors
      .supervise {
        Behaviors.withTimers[Command] { timer =>
          timer.startTimerAtFixedRate(ReportMemoryStats, delay)
          active(props)
        }
      }
      .onFailure[Exception](
        SupervisorStrategy.restart.withLimit(Int.MaxValue, delay)
      )
  }

  def active(props: BehaviorProps): Behavior[Command] =
    Behaviors.receive {

      case (_, ReportMemoryStats) =>
        logger.debug("active(ReportMemoryStats)")

        val runtime = Runtime.getRuntime

        props.metrics.gauge(
          "memory.bytes",
          (runtime.totalMemory - runtime.freeMemory)
        )

        Behaviors.same

      case (_, Shutdown(replyTo)) =>
        logger.debug("active(Shutdown)")
        Behaviors.stopped { () =>
          replyTo ! Done
        }

      case (_, msg) =>
        logger.debug(s"active(${msg})")
        Behaviors.unhandled

    }

}
