package com.openbank.dwh.boot

import akka.actor.{ActorSystem, Scheduler}
import akka.stream.{ActorMaterializer, Materializer}
import scala.concurrent.ExecutionContext


trait AkkaModule {
  self: ConfigModule =>

  implicit lazy val system: ActorSystem = ActorSystem("dwh", config)
  implicit lazy val materializer: Materializer = ActorMaterializer()
  implicit lazy val scheduler: Scheduler = system.scheduler
  implicit lazy val defaultExecutionContext: ExecutionContext = system.dispatcher

  lazy val primaryExplorationExecutionContext: ExecutionContext = system.dispatchers.lookup("dispatchers.primary-exploration")
}

