package com.openbank.dwh.actor

import akka.Done
import akka.util.Timeout
import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior, SupervisorStrategy}
import scala.concurrent.{Future, ExecutionContext}
import com.typesafe.scalalogging.StrictLogging
import com.openbank.dwh.service._
import scala.concurrent.duration._

object GuardianActor extends StrictLogging {

  val name = "guardian"

  trait Command

  case object StartActors extends Command
  case class StopActors(replyTo: ActorRef[Done]) extends Command

  case object RunPrimaryDataExploration extends Command

  case class BehaviorProps(
      ctx: ActorContext[Command],
      primaryDataExplorationService: PrimaryDataExplorationService
  )

  def apply(
      primaryDataExplorationService: PrimaryDataExplorationService
  )(implicit ec: ExecutionContext): Behavior[Command] = {
    Behaviors
      .supervise {
        Behaviors.setup { (ctx: ActorContext[Command]) =>
          val props = BehaviorProps(ctx, primaryDataExplorationService)
          behaviour(props)
        }
      }
      .onFailure(SupervisorStrategy.restart.withStopChildren(false))
  }

  def behaviour(
      props: BehaviorProps
  )(implicit ec: ExecutionContext): Behavior[Command] =
    Behaviors.receiveMessagePartial {

      case StartActors =>
        getRunningActor(props.ctx, PrimaryDataExplorerActor.name) match {
          case None =>
            logger.info("Starting PrimaryDataExplorerActor")
            props.ctx.spawn(
              PrimaryDataExplorerActor(props.primaryDataExplorationService),
              PrimaryDataExplorerActor.name
            )
            props.ctx.self ! RunPrimaryDataExploration
          case _ =>
        }
        Behaviors.same

      case StopActors(replyTo) =>
        Future
          .sequence {
            props.ctx.children.toSeq.map {

              case ref: ActorRef[Nothing]
                  if ref.path.name == PrimaryDataExplorerActor.name =>
                logger.info("Stopping PrimaryDataExplorerActor")
                ref
                  .asInstanceOf[ActorRef[Command]]
                  .ask[Done](PrimaryDataExplorerActor.Shutdown)(
                    Timeout(5.seconds),
                    props.ctx.system.scheduler
                  )
                  .recoverWith {
                    case _: Exception =>
                      props.ctx.stop(ref)
                      Future.successful(Done)
                  }

              case ref: ActorRef[Nothing] =>
                logger.warn(s"Unknown child actor ${ref.path}")
                props.ctx.stop(ref)
                Future.successful(Done)

              case node =>
                logger.warn(s"Unknown child ${node}")
                Future.successful(Done)

            }
          }
          .map(_ => Done)
          .recoverWith {
            case e: Exception =>
              logger.warn(s"Exception occured during shutdown ${e}")
              Future.successful(Done)
          }
          .onComplete { _ => replyTo ! Done }

        Behaviors.stopped

      case RunPrimaryDataExploration =>
        getRunningActor(props.ctx, PrimaryDataExplorerActor.name) match {
          case Some(ref) => ref ! PrimaryDataExplorerActor.RunExploration
          case _         => logger.info("Cannot run primary data exploration")
        }
        Behaviors.same

      case _ =>
        Behaviors.unhandled

    }

  private def getRunningActor(
      ctx: ActorContext[Command],
      name: String
  ): Option[ActorRef[Command]] = {
    ctx.child(name) match {
      case actor: Some[ActorRef[Nothing]] =>
        actor.map(_.asInstanceOf[ActorRef[Command]])
      case _ => None
    }
  }

}
