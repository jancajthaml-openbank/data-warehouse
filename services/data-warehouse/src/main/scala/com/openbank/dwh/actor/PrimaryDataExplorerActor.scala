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
  case class PoisonPill(promise: Promise[Done]) extends Command
  case object Lock extends Command
  case object Free extends Command

  private lazy val delay = 5.seconds

  def apply(primaryDataExplorationService: PrimaryDataExplorationService)(implicit ec: ExecutionContext) = {

    def active(): Behavior[Command] = Behaviors.receive { (context, m) => m match {

      case Free =>
        idle()

      case PoisonPill(promise) =>
        promise.completeWith(primaryDataExplorationService.killRunningWorkflow())
        Behaviors.stopped

      case RunExploration =>
        Behaviors.same

      case _ =>
        Behaviors.unhandled

    } }

    def idle(): Behavior[Command] = Behaviors.receive { (context, m) => m match {

      case Lock =>
        active()

      case RunExploration =>
        Future.successful(Done)
          .map {
            case _ if primaryDataExplorationService.isStoragePristine() =>
              logger.debug("Skipping Primary Data Exploration")
              throw PrimaryStoragePristine
            case _ =>
              logger.debug("Running Primary Data Exploration")
              Done
          }
          .flatMap { _ => primaryDataExplorationService.exploreAccounts() }
          .flatMap { _ => primaryDataExplorationService.exploreTransfers() }
          .recoverWith { case e: Exception => Future.successful(Done) }
          .onComplete { _ => context.self ! Free }

        context.self ! Lock

        Behaviors.same

      case PoisonPill(promise) =>
        promise.success(Done)
        Behaviors.stopped

      case _ =>
        Behaviors.unhandled

    } }

    Behaviors
      .supervise {
        Behaviors.withTimers[Command] { timer =>
          timer.startTimerWithFixedDelay(RunExploration, delay)
          idle()
        }
      }
      .onFailure[Exception](SupervisorStrategy.restart.withLimit(Int.MaxValue, delay))
  }

}
