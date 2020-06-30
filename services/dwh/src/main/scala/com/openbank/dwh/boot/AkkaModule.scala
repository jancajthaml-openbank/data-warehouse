package com.openbank.dwh.boot

import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, Materializer}
import scala.concurrent.ExecutionContext


trait AkkaModule {
  self: ConfigModule =>

  implicit lazy val system: ActorSystem = ActorSystem("dwh", config)
  implicit lazy val materializer: Materializer = ActorMaterializer()
  implicit lazy val executionContext: ExecutionContext = system.dispatcher
}

