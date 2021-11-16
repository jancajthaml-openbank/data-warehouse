package com.openbank.dwh.actor

import akka.Done
import akka.util.Timeout
import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior, MailboxSelector, SupervisorStrategy}
import scala.concurrent.{ExecutionContextExecutor, Future}
import com.typesafe.scalalogging.StrictLogging
import scala.concurrent.duration._
import com.openbank.dwh.boot.{MetricsModule, ServiceModule}

object Guardian {

  val name = "guardian"

  trait Command

  case class Bootstrap(replyTo: ActorRef[Done], service: ServiceModule with MetricsModule)
      extends Command

  case class Shutdown(replyTo: ActorRef[Done]) extends Command

}

object GuardianActor extends StrictLogging {

  import Guardian._

  private var nameToActor = Map.empty[String, ActorRef[Command]]

  def apply(): Behavior[Command] = {
    Behaviors.setup { (ctx: ActorContext[Command]) =>
      behaviour(ctx)
    }
  }

  def behaviour(ctx: ActorContext[Command]): Behavior[Command] =
    Behaviors.receiveMessagePartial {

      case Bootstrap(replyTo, service) =>
        nameToActor.get(PrimaryDataExplorer.name) match {
          case Some(_) => {}
          case None =>
            logger.info("Starting {}/{}", ctx.self.path, PrimaryDataExplorer.name)
            val ref = ctx.spawn(
              PrimaryDataExplorerActor(service.primaryDataExplorationService),
              PrimaryDataExplorer.name,
              MailboxSelector.fromConfig(s"akka.actor.${PrimaryDataExplorer.name}-mailbox")
            )
            nameToActor += PrimaryDataExplorer.name -> ref
        }

        nameToActor.get(MemoryMonitor.name) match {
          case Some(_) => {}
          case None =>
            logger.info("Starting {}/{}", ctx.self.path, MemoryMonitor.name)
            val ref = ctx.spawn(
              MemoryMonitorActor(service.metrics),
              MemoryMonitor.name,
              MailboxSelector.fromConfig(s"akka.actor.${MemoryMonitor.name}-mailbox")
            )
            nameToActor += MemoryMonitor.name -> ref
        }

        replyTo ! Done

        Behaviors.same

      case Shutdown(replyTo) =>
        implicit val ec: ExecutionContextExecutor = ctx.executionContext

        Future
          .sequence {

            nameToActor.values.map { case ref =>
              logger.warn("Stopping {}", ref.path)
              ref
                .ask[Done](Shutdown)(
                  Timeout(5.seconds),
                  ctx.system.scheduler
                )
                .recoverWith { case _: Exception =>
                  ctx.stop(ref)
                  Future.successful(Done)
                }(ctx.executionContext)

            }
          }
          .map(_ => Done)
          .recoverWith { case e: Exception =>
            logger.warn("Exception occurred during shutdown", e)
            Future.successful(Done)
          }
          .onComplete { _ => replyTo ! Done }

        Behaviors.same

      case msg: PrimaryDataExplorer.Command =>
        nameToActor.get(PrimaryDataExplorer.name) match {
          case Some(ref) => ref ! msg
          case None =>
            logger.info("Cannot run primary data exploration")
        }

        Behaviors.same

    }

}
