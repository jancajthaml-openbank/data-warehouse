package com.openbank.dwh.actor

import akka.Done
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{Behavior, SupervisorStrategy}
import akka.dispatch.{PriorityGenerator, UnboundedPriorityMailbox}
import com.typesafe.scalalogging.StrictLogging
import akka.actor.ActorSystem.Settings

import scala.concurrent.duration._
import com.openbank.dwh.service._

import scala.annotation.unused
import scala.util.{Failure, Success}

object PrimaryDataExplorer {

  val name = "primary-data-explorer"

  sealed trait Command extends Guardian.Command

  case object RunExploration extends Command

  case object Lock extends Command

  case object Free extends Command

}

object PrimaryDataExplorerActor {

  import PrimaryDataExplorer._

  private lazy val delay = 2.seconds

  def apply(
      primaryDataExplorationService: PrimaryDataExplorationService
  ): Behavior[Guardian.Command] = {

    Behaviors
      .supervise {
        Behaviors.withTimers[Guardian.Command] { timer =>
          timer.startTimerAtFixedRate(RunExploration, delay)
          val ref = new PrimaryDataExplorerActor(primaryDataExplorationService)
          ref.idle()
        }
      }
      .onFailure[Exception](
        SupervisorStrategy.restart.withLimit(Int.MaxValue, delay)
      )
  }

}

class PrimaryDataExplorerActor(primaryDataExplorationService: PrimaryDataExplorationService)
    extends StrictLogging {

  import PrimaryDataExplorer._

  def active(): Behavior[Guardian.Command] =
    Behaviors.receive {

      case (_, Lock) =>
        logger.debug("active(Lock)")
        Behaviors.same

      case (_, Free) =>
        logger.debug("active(Free)")
        idle()

      case (ctx, Guardian.Shutdown(replyTo)) =>
        logger.debug("active(Shutdown)")
        Behaviors.stopped { () =>
          primaryDataExplorationService
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

  def idle(): Behavior[Guardian.Command] =
    Behaviors.receive {

      case (_, Lock) =>
        logger.debug("idle(Lock)")
        active()

      case (_, Free) =>
        logger.debug("idle(Free)")
        Behaviors.same

      case (ctx, RunExploration) =>
        logger.debug("idle(RunExploration)")

        ctx.self ! Lock

        primaryDataExplorationService
          .runExploration()
          .onComplete {
            case Success(_) =>
              ctx.self ! Free
            case Failure(e) =>
              logger.error("Primary exploration failed", e)
              ctx.self ! Free
          }(ctx.executionContext)

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

}

class PrimaryDataExplorerMailbox(
    @unused settings: Settings,
    @unused config: com.typesafe.config.Config
) extends UnboundedPriorityMailbox(
      PriorityGenerator { case _ =>
        0
      }
    )
