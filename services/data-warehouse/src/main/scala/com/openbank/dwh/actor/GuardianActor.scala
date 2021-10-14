package com.openbank.dwh.actor

import akka.Done
import akka.util.Timeout
import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior, SupervisorStrategy}

import scala.concurrent.{ExecutionContextExecutor, Future}
import com.typesafe.scalalogging.StrictLogging
import com.openbank.dwh.service._

import scala.concurrent.duration._
import com.openbank.dwh.metrics.StatsDClient

object Guardian {

  val name = "guardian"

  trait Command

  case object StartActors extends Command

  case class Shutdown(replyTo: ActorRef[Done]) extends Command

}

object GuardianActor extends StrictLogging {

  import Guardian._

  case class BehaviorProps(
      ctx: ActorContext[Command],
      primaryDataExplorationService: PrimaryDataExplorationService,
      metrics: StatsDClient
  )

  def apply(
      primaryDataExplorationService: PrimaryDataExplorationService,
      metrics: StatsDClient
  ): Behavior[Command] = {
    Behaviors
      .supervise {
        Behaviors.setup { (ctx: ActorContext[Command]) =>
          val props = BehaviorProps(ctx, primaryDataExplorationService, metrics)
          behaviour(props)
        }
      }
      .onFailure(SupervisorStrategy.restart.withStopChildren(false))
  }

  def behaviour(
      props: BehaviorProps
  ): Behavior[Command] =
    Behaviors.receiveMessagePartial {

      case StartActors =>
        getRunningActor(props.ctx, PrimaryDataExplorer.name) match {
          case None =>
            logger.info("Starting PrimaryDataExplorerActor")
            props.ctx.spawn(
              PrimaryDataExplorerActor(props.primaryDataExplorationService),
              PrimaryDataExplorer.name
            )
            props.ctx.self ! PrimaryDataExplorer.RunExploration
          case _ =>
        }

        getRunningActor(props.ctx, MemoryMonitor.name) match {
          case None =>
            logger.info("Starting MemoryMonitorActor")
            props.ctx.spawn(
              MemoryMonitorActor(props.metrics),
              MemoryMonitor.name
            )
          case _ =>
        }

        Behaviors.same

      case Shutdown(replyTo) =>
        implicit val ec: ExecutionContextExecutor = props.ctx.executionContext

        Future
          .sequence {
            props.ctx.children.toSeq.map {

              case ref: ActorRef[Nothing] =>
                logger.warn("Stopping {}", ref.path)
                ref
                  .asInstanceOf[ActorRef[Command]]
                  .ask[Done](Shutdown)(
                    Timeout(5.seconds),
                    props.ctx.system.scheduler
                  )
                  .recoverWith { case _: Exception =>
                    props.ctx.stop(ref)
                    Future.successful(Done)
                  }(props.ctx.executionContext)

              case node =>
                logger.warn("Unknown child {}", node)
                Future.successful(Done)

            }
          }
          .map(_ => Done)
          .recoverWith { case e: Exception =>
            logger.warn("Exception occurred during shutdown", e)
            Future.successful(Done)
          }
          .onComplete { _ => replyTo ! Done }

        Behaviors.stopped

      case PrimaryDataExplorer.RunExploration =>
        getRunningActor(props.ctx, PrimaryDataExplorer.name) match {
          case Some(ref) => ref ! PrimaryDataExplorer.RunExploration
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
