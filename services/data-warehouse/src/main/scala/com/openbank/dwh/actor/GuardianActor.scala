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
  case class ShutdownActors(ack: Promise[Done]) extends Command
  case object RunPrimaryDataExploration extends Command

  case class BehaviorProps(ctx: ActorContext[Command], primaryDataExplorationService: PrimaryDataExplorationService)

  def apply(primaryDataExplorationService: PrimaryDataExplorationService)(implicit ec: ExecutionContext): Behavior[Command] = {
    Behaviors
      .supervise {
        Behaviors.setup {
          (ctx: ActorContext[Command]) => {
            val props = BehaviorProps(ctx, primaryDataExplorationService)
            behaviour(props)
          }
        }
      }
      .onFailure(SupervisorStrategy.restart.withStopChildren(false))
  }

  def behaviour(props: BehaviorProps)(implicit ec: ExecutionContext): Behavior[Command] = Behaviors.receiveMessagePartial {

    case StartActors =>
      getRunningActor(props.ctx, PrimaryDataExplorerActor.namespace) match {
        case None =>
          logger.info("Starting PrimaryDataExplorerActor")
          logger.debug("Starting PrimaryDataExplorerActor")
          props.ctx.spawn(
            PrimaryDataExplorerActor(props.primaryDataExplorationService),
            PrimaryDataExplorerActor.namespace
          )
          props.ctx.self ! RunPrimaryDataExploration
        case _ =>
      }
      Behaviors.same

    case ShutdownActors(ack) =>
      getRunningActor(props.ctx, PrimaryDataExplorerActor.namespace) match {
        case Some(ref) =>
          logger.info("Stopping PrimaryDataExplorerActor")
          ref ! PrimaryDataExplorerActor.Shutdown(ack)
        case _ =>
      }
      Behaviors.same

    case RunPrimaryDataExploration =>
      getRunningActor(props.ctx, PrimaryDataExplorerActor.namespace) match {
        case Some(ref) => {
          logger.info("Invoking PrimaryDataExplorerActor.RunExploration")
          logger.debug("Invoking PrimaryDataExplorerActor.RunExploration")
          ref ! PrimaryDataExplorerActor.RunExploration
        }
        case _ => logger.info("Cannot run primary data exploration")
      }
      Behaviors.same

    case _ =>
      Behaviors.unhandled

  }

  private def getRunningActor(ctx: ActorContext[Command], name: String): Option[ActorRef[Command]] = {
    ctx.child(name) match {
      case actor: Some[ActorRef[Nothing]] => actor.map(_.asInstanceOf[ActorRef[Command]])
      case _ => None
    }
  }

}
