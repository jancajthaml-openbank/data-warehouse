package com.openbank.dwh.actor

import akka.Done
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior, SupervisorStrategy}
import scala.concurrent.{ExecutionContext, Future}
import com.typesafe.scalalogging.StrictLogging
import scala.concurrent.duration._
import com.openbank.dwh.service._

object PrimaryDataExplorerActor extends StrictLogging {

  val name = "primary-data-explorer"

  sealed trait Command extends GuardianActor.Command
  case object RunExploration extends Command
  case class Shutdown(replyTo: ActorRef[Done]) extends Command
  case object Lock extends Command
  case object Free extends Command

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
        Behaviors.withTimers[Command] { timer =>
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
  )(implicit ec: ExecutionContext): Behavior[Command] =
    Behaviors.receive {

      case (_, Lock) =>
        logger.debug("active(Lock)")
        Behaviors.same

      case (_, Free) =>
        logger.debug("active(Free)")
        idle(props)

      case (_, Shutdown(replyTo)) =>
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
  )(implicit ec: ExecutionContext): Behavior[Command] =
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

        // FIXME try in single flow
        Future
          .successful(Done)
          .flatMap { _ =>
            props.primaryDataExplorationService.exploreAccounts()
          }
          .flatMap { _ =>
            props.primaryDataExplorationService.exploreTransfers()
          }
          .recoverWith {
            case e: Exception =>
              logger.error(s"Primary exploration errored with", e)
              Future.successful(Done)
          }
          .onComplete { _ => ctx.self ! Free }

        Behaviors.same

      case (_, Shutdown(replyTo)) =>
        logger.debug("idle(Shutdown)")
        Behaviors.stopped { () =>
          replyTo ! Done
        }

      case (_, msg) =>
        logger.debug(s"idle(${msg})")
        Behaviors.unhandled

    }

}
