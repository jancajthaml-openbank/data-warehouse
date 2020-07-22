package com.openbank.dwh.actor

import akka.Done
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{Behavior, SupervisorStrategy}
import scala.concurrent.{ExecutionContext, Future, Promise}
import com.typesafe.scalalogging.StrictLogging
import scala.concurrent.duration._
import com.openbank.dwh.service._


case object PrimaryStoragePristine extends Exception("", None.orNull)

object PrimaryDataExplorerActor extends StrictLogging {

  val namespace = "primary-data-explorer"

  sealed trait Command extends GuardianActor.Command
  case object RunExploration extends Command
  case class Shutdown(promise: Promise[Done]) extends Command
  case object Lock extends Command
  case object Free extends Command

  case class BehaviorProps(primaryDataExplorationService: PrimaryDataExplorationService)

  private lazy val delay = 5.seconds

  def apply(primaryDataExplorationService: PrimaryDataExplorationService)(implicit ec: ExecutionContext) = {
    val props = BehaviorProps(primaryDataExplorationService)

    Behaviors
      .supervise {
        Behaviors.withTimers[Command] { timer =>
          timer.startTimerWithFixedDelay(RunExploration, delay)
          idle(props)
        }
      }
      .onFailure[Exception](SupervisorStrategy.restart.withLimit(Int.MaxValue, delay))
  }

  def active(props: BehaviorProps)(implicit ec: ExecutionContext): Behavior[Command] =
    Behaviors.receive { (context, m) => m match {

      case Lock =>
        logger.debug("active(Lock)")
        Behaviors.same

      case Free =>
        logger.debug("active(Free)")
        idle(props)

      case Shutdown(promise) =>
        logger.debug("active(Shutdown)")
        promise.completeWith(props.primaryDataExplorationService.killRunningWorkflow())
        Behaviors.stopped

      case RunExploration =>
        logger.debug("active(RunExploration)")
        Behaviors.same

      case msg =>
        logger.debug(s"active(${msg})")
        Behaviors.unhandled

    }
  }

  def idle(props: BehaviorProps)(implicit ec: ExecutionContext): Behavior[Command] =
    Behaviors.receive { (context, m) => m match {

      case Lock =>
        logger.debug("idle(Lock)")
        active(props)

      case Free =>
        logger.debug("idle(Free)")
        Behaviors.same

      case RunExploration =>
        logger.debug("idle(RunExploration)")

        context.self ! Lock

        Future.successful(Done)
          .map {
            case _ if props.primaryDataExplorationService.isStoragePristine() =>
              logger.debug("Skipping Primary Data Exploration")
              throw PrimaryStoragePristine
            case _ =>
              logger.debug("Running Primary Data Exploration")
              Done
          }
          .flatMap { _ => props.primaryDataExplorationService.exploreAccounts() }
          .flatMap { _ => props.primaryDataExplorationService.exploreTransfers() }
          .recoverWith { case e: Exception => Future.successful(Done) }
          .onComplete { _ =>
            logger.debug("Finished Primary Data Exploration")
            context.self ! Free
          }

        Behaviors.same

      case Shutdown(promise) =>
        logger.debug("idle(Shutdown)")
        promise.success(Done)
        Behaviors.stopped

      case msg =>
        logger.debug(s"idle(${msg})")
        Behaviors.unhandled

    }
  }

}
