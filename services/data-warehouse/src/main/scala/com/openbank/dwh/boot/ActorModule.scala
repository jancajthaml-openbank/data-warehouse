package com.openbank.dwh.boot

import akka.Done
import akka.util.Timeout
import scala.concurrent.duration._
import akka.actor.typed.scaladsl.AskPattern._
import com.typesafe.scalalogging.StrictLogging
import scala.concurrent.Future
import com.openbank.dwh.actor.Guardian

trait ActorModule

trait ProductionActorModule extends ActorModule with Lifecycle {
  self: AkkaModule with ServiceModule with MetricsModule with StrictLogging =>

  abstract override def stop(): Future[Done] = {
    logger.info("Stopping akka://{}", Guardian.name)
    typedSystem
      .ask[Done](Guardian.Shutdown)(Timeout(5.seconds), typedSystem.scheduler)
      .flatMap(_ => super.stop())
  }

  abstract override def start(): Future[Done] = {
    logger.info("Starting akka://{}", Guardian.name)
    typedSystem ! Guardian.StartActors(this) // FIXME ack and wait until started
    super.start()
  }

}
