package com.openbank.dwh.actor

import akka.Done
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{Behavior, SupervisorStrategy}
import com.typesafe.scalalogging.StrictLogging
import scala.concurrent.duration._
import com.openbank.dwh.service._

object PrimaryDataExplorer {

  val name = "primary-data-explorer"

  case object RunExploration extends Guardian.Command

  case object Lock extends Guardian.Command

  case object Free extends Guardian.Command

}

object PrimaryDataExplorerActor extends StrictLogging {

  import PrimaryDataExplorer._

  case class ActorProperties(
      primaryDataExplorationService: PrimaryDataExplorationService
  )

  private lazy val delay = 2.seconds

  def apply(
      primaryDataExplorationService: PrimaryDataExplorationService
  ): Behavior[Guardian.Command] = {
    val props = ActorProperties(primaryDataExplorationService)

    Behaviors
      .supervise {
        Behaviors.withTimers[Guardian.Command] { timer =>
          timer.startTimerAtFixedRate(RunExploration, delay)
          idle(props)
        }
      }
      .onFailure[Exception](
        SupervisorStrategy.restart.withLimit(Int.MaxValue, delay)
      )
  }

  def active(
      props: ActorProperties
  ): Behavior[Guardian.Command] =
    Behaviors.receive {

      case (_, Lock) =>
        logger.debug("active(Lock)")
        Behaviors.same

      case (_, Free) =>
        logger.debug("active(Free)")
        idle(props)

      case (ctx, Guardian.Shutdown(replyTo)) =>
        logger.debug("active(Shutdown)")
        Behaviors.stopped { () =>
          props.primaryDataExplorationService
            .killRunningWorkflow()
            .onComplete { _ => replyTo ! Done }(ctx.executionContext)
        }

      case (_, RunExploration) =>
        logger.debug("active(RunExploration)")
        Behaviors.same

      case (_, msg) =>
        logger.debug("active({})", msg)
        Behaviors.unhandled

    }

  def idle(
      props: ActorProperties
  ): Behavior[Guardian.Command] =
    Behaviors.receive {

      case (_, Lock) =>
        logger.debug("idle(Lock)")
        active(props)

      case (_, Free) =>
        logger.debug("idle(Free)")
        Behaviors.same

      case (ctx, RunExploration) =>
        logger.debug("idle(RunExploration)")

        ctx.self ! Lock

        props.primaryDataExplorationService
          .runExploration()
          .onComplete { _ => ctx.self ! Free }(ctx.executionContext)

        Behaviors.same

      case (_, Guardian.Shutdown(replyTo)) =>
        logger.debug("idle(Shutdown)")
        Behaviors.stopped { () =>
          replyTo ! Done
        }

      case (_, msg) =>
        logger.debug("idle({})", msg)
        Behaviors.unhandled

    }

  // TODO priority backlog

}
