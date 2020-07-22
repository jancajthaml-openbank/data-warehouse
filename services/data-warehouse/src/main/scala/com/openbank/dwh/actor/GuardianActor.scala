package com.openbank.dwh.actor

import akka.Done
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior, SupervisorStrategy}
import scala.concurrent.{Future, Promise, ExecutionContext}
import com.typesafe.scalalogging.StrictLogging
import com.openbank.dwh.service._


object GuardianActor extends StrictLogging {

  val namespace = "guardian"

  trait Command
  case object StartActors extends Command
  case class ShutdownActors(promise: Promise[Done]) extends Command
  case object RunPrimaryDataExploration extends Command

  def apply(primaryDataExplorationService: PrimaryDataExplorationService)(implicit ec: ExecutionContext): Behavior[Command] = {

    def behaviour(context: ActorContext[Command]): Behavior[Command] = Behaviors.receiveMessagePartial {

      case StartActors =>
        getRunningActor(context, PrimaryDataExplorerActor.namespace) match {
          case None =>
            logger.info("Starting PrimaryDataExplorerActor")
            context.spawn(
              PrimaryDataExplorerActor(primaryDataExplorationService),
              PrimaryDataExplorerActor.namespace
            )
            context.self ! RunPrimaryDataExploration
          case _ =>
        }
        Behaviors.same

      case ShutdownActors(promise) =>
        getRunningActor(context, PrimaryDataExplorerActor.namespace) match {
          case Some(ref) =>
            logger.info("Stopping PrimaryDataExplorerActor")
            ref ! PrimaryDataExplorerActor.PoisonPill(promise)
          case _ =>
        }
        Behaviors.same

      case RunPrimaryDataExploration =>
        getRunningActor(context, PrimaryDataExplorerActor.namespace) match {
          case Some(ref) => {
            logger.info("Invoking Primary Data Exploration")
            ref ! PrimaryDataExplorerActor.RunExploration
          }
          case _ => logger.info("Cannot run primary data exploration")
        }
        Behaviors.same

      case _ =>
        Behaviors.unhandled

    }

    Behaviors
      .supervise {
        Behaviors.setup((context: ActorContext[Command]) => {
          behaviour(context)
        })
      }
      .onFailure(SupervisorStrategy.restart.withStopChildren(false))
  }

  private def getRunningActor(context: ActorContext[Command], name: String): Option[ActorRef[Command]] = {
    context.child(name) match {
      case actor: Some[ActorRef[Nothing]] => actor.map(_.asInstanceOf[ActorRef[Command]])
      case _ => None
    }
  }

}
