package com.openbank.dwh

import akka.Done
import com.openbank.dwh.boot._
import com.typesafe.scalalogging.LazyLogging
import scala.concurrent.{Future, Await}
import scala.concurrent.duration._
import scala.sys

object Main extends App {

  object Program
    extends ProgramLifecycle
    with ModulesLifecycle
    with GlobalConfigModule
    with TypedActorModule
    with ServiceModule
    with PersistenceModule
    with RouterModule
    with AkkaModule
    with LazyLogging

  try {
    Await.result(Program.setup(), 10.minutes)
    sys.addShutdownHook { Program.shutdown() }
    Program.start()
  } catch {
    case e: Exception =>
      e.printStackTrace()
      Program.stop()
  }
}
