package com.openbank.dwh

import akka.Done
import com.openbank.dwh.boot._
import com.typesafe.scalalogging.StrictLogging
import scala.concurrent.{ExecutionContext, Future, Await}
import scala.concurrent.duration._
import scala.sys

object Main extends App with StrictLogging {

  object Program
    extends ProgramLifecycle
    with ModulesLifecycle
    with GlobalConfigModule
    with TypedActorModule
    with ServiceModule
    with PersistenceModule
    with RouterModule
    with AkkaModule
    with StrictLogging

  try {
    Await.result(Program.setup(), 10.minutes)
    sys.addShutdownHook { Program.shutdown() }
    Program.start()
  } catch {
    case e: Exception =>
      implicit val ec = ExecutionContext.global
      e.printStackTrace()
      Program
        .stop()
        .onComplete { _ => System.exit(1) }
  }
}
