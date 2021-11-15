package com.openbank.dwh.actor

import akka.Done
import akka.util.Timeout
import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior, SupervisorStrategy}
import scala.concurrent.{ExecutionContextExecutor, Future}
import com.typesafe.scalalogging.StrictLogging
import scala.concurrent.duration._
import com.openbank.dwh.boot.{ServiceModule, MetricsModule}

object Guardian {

  val name = "guardian"

  trait Command

  case class StartActors(serviceModule: ServiceModule with MetricsModule) extends Command

  case class Shutdown(replyTo: ActorRef[Done]) extends Command

}

object GuardianActor extends StrictLogging {

  import Guardian._

  def apply(): Behavior[Command] = {
    Behaviors
      .supervise {
        Behaviors.setup { (ctx: ActorContext[Command]) =>
          behaviour(ctx)
        }
      }
      .onFailure(SupervisorStrategy.restart.withStopChildren(false))
  }

  def behaviour(ctx: ActorContext[Command]): Behavior[Command] =
    Behaviors.receiveMessagePartial {

      case StartActors(serviceModule) =>
        getRunningActor(ctx, PrimaryDataExplorer.name) match {
          case None =>
            logger.info("Starting {}/{}", ctx.self.path, PrimaryDataExplorer.name)
            ctx.spawn(
              PrimaryDataExplorerActor(serviceModule.primaryDataExplorationService),
              PrimaryDataExplorer.name
            )
            ctx.self ! PrimaryDataExplorer.RunExploration
          case _ =>
        }

        getRunningActor(ctx, MemoryMonitor.name) match {
          case None =>
            logger.info("Starting {}/{}", ctx.self.path, MemoryMonitor.name)
            ctx.spawn(
              MemoryMonitorActor(serviceModule.metrics),
              MemoryMonitor.name
            )
          case _ =>
        }

        Behaviors.same

      case Shutdown(replyTo) =>
        implicit val ec: ExecutionContextExecutor = ctx.executionContext

        Future
          .sequence {

            ctx.children.toSeq.map {

              case ref: ActorRef[Nothing] =>
                logger.warn("Stopping {}", ref.path)
                ref
                  .asInstanceOf[ActorRef[Command]]
                  .ask[Done](Shutdown)(
                    Timeout(5.seconds),
                    ctx.system.scheduler
                  )
                  .recoverWith { case _: Exception =>
                    ctx.stop(ref)
                    Future.successful(Done)
                  }(ctx.executionContext)

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
        getRunningActor(ctx, PrimaryDataExplorer.name) match {
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
