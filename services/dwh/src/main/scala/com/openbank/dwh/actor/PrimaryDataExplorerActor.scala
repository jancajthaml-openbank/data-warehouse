package com.openbank.dwh.actor

import akka.Done
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{Behavior, SupervisorStrategy}
import scala.concurrent.{ExecutionContext, Future}
import com.typesafe.scalalogging.StrictLogging
import scala.concurrent.duration._
import com.openbank.dwh.service._


object PrimaryDataExplorerActor extends StrictLogging {

  val namespace = "primary-data-explorer"

  sealed trait Command extends GuardianActor.Command
  case object RunExploration extends Command
  case object Lock extends Command
  case object Free extends Command

  private lazy val delay = 10.minutes

  def apply(primaryDataExplorationService: PrimaryDataExplorationService)(implicit ec: ExecutionContext) = {

    def active(): Behavior[Command] = Behaviors.receive { (context, m) => m match {

      case Free =>
        logger.debug("Unlocking exploration")
        idle()

      case RunExploration =>
        logger.info("Primary Data Exploration is already running")
        Behaviors.same

      case _ =>
        Behaviors.unhandled

    } }

    def idle(): Behavior[Command] = Behaviors.receive { (context, m) => m match {

      case Lock =>
        logger.debug("Locking exploration")
        active()

      case RunExploration =>
        logger.info("Run Primary Data Exploration now")

        primaryDataExplorationService
          .runExploration
          .recoverWith { case e: Exception => Future.successful(Done) }
          .onComplete { _ => context.self ! Free }

        context.self ! Lock

        Behaviors.same

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
