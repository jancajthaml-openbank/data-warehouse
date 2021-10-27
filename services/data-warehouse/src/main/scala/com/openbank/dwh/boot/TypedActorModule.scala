package com.openbank.dwh.boot

import akka.Done
import akka.actor.typed.ActorSystem
import akka.util.Timeout

import scala.concurrent.duration._
import akka.actor.typed.scaladsl.AskPattern._
import com.typesafe.scalalogging.StrictLogging

import scala.concurrent.{ExecutionContext, Future}
import com.openbank.dwh.actor.{Guardian, GuardianActor}

trait TypedActorModule extends Lifecycle {
  self: ServiceModule with MetricsModule with StrictLogging =>

  lazy val typedSystem: ActorSystem[Guardian.Command] = ActorSystem(
    GuardianActor(primaryDataExplorationService, metrics),
    Guardian.name
  )

  abstract override def stop(): Future[Done] = {
		implicit val ec: ExecutionContext = typedSystem.executionContext

		logger.info("Stopping akka://{}", Guardian.name)

		typedSystem
			.ask[Done](Guardian.Shutdown)(Timeout(5.seconds), typedSystem.scheduler)
      .flatMap(_ => super.stop())
  }

  abstract override def start(): Future[Done] = {
		logger.info("Starting akka://{}", Guardian.name)
    typedSystem ! Guardian.StartActors
    super.start()
  }

}
