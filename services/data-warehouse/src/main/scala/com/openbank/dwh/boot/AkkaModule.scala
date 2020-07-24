package com.openbank.dwh.boot

import akka.actor.{ActorSystem, Scheduler}
import akka.stream.{ActorMaterializer, Materializer, SystemMaterializer}
import scala.concurrent.ExecutionContext

trait AkkaModule {
  self: ConfigModule =>

  implicit lazy val system: ActorSystem = ActorSystem.create("dwh", config)

  implicit lazy val materializer: Materializer = SystemMaterializer(
    system
  ).materializer

  implicit lazy val scheduler: Scheduler = system.scheduler

  implicit lazy val defaultExecutionContext: ExecutionContext =
    system.dispatcher

  lazy val dataExplorationExecutionContext: ExecutionContext =
    system.dispatchers.lookup("data-exploration.dispatcher")

  lazy val graphQLExecutionContext: ExecutionContext =
    system.dispatchers.lookup("graphql.dispatcher")

}
