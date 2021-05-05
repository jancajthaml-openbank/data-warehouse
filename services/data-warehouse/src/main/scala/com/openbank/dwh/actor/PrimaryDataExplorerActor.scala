package com.openbank.dwh.actor

import akka.Done
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{Behavior, SupervisorStrategy}
import scala.concurrent.{ExecutionContext, Future}
import com.typesafe.scalalogging.StrictLogging
import scala.concurrent.duration._
import com.openbank.dwh.service._

object PrimaryDataExplorer extends StrictLogging {

  val name = "primary-data-explorer"

  case object RunExploration extends Guardian.Command
  case object Lock extends Guardian.Command
  case object Free extends Guardian.Command

}

object PrimaryDataExplorerActor extends StrictLogging {

  import PrimaryDataExplorer._

  case class BehaviorProps(
      primaryDataExplorationService: PrimaryDataExplorationService
  )

  private lazy val delay = 2.seconds

  def apply(
      primaryDataExplorationService: PrimaryDataExplorationService
  )(implicit ec: ExecutionContext) = {
    val props = BehaviorProps(primaryDataExplorationService)

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
      props: BehaviorProps
  )(implicit ec: ExecutionContext): Behavior[Guardian.Command] =
    Behaviors.receive {

      case (_, Lock) =>
        logger.debug("active(Lock)")
        Behaviors.same

      case (_, Free) =>
        logger.debug("active(Free)")
        idle(props)

      case (_, Guardian.Shutdown(replyTo)) =>
        logger.debug("active(Shutdown)")
        Behaviors.stopped { () =>
          props.primaryDataExplorationService
            .killRunningWorkflow()
            .onComplete { _ => replyTo ! Done }
        }

      case (_, RunExploration) =>
        logger.debug("active(RunExploration)")
        Behaviors.same

      case (_, msg) =>
        logger.debug(s"active(${msg})")
        Behaviors.unhandled

    }

  def idle(
      props: BehaviorProps
  )(implicit ec: ExecutionContext): Behavior[Guardian.Command] =
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
          .recoverWith { case e: Exception =>
            logger.error(s"Primary exploration errored with", e)
            Future.successful(Done)
          }
          .onComplete { _ => ctx.self ! Free }

        Behaviors.same

      case (_, Guardian.Shutdown(replyTo)) =>
        logger.debug("idle(Shutdown)")
        Behaviors.stopped { () =>
          replyTo ! Done
        }

      case (_, msg) =>
        logger.debug(s"idle(${msg})")
        Behaviors.unhandled

    }

}
