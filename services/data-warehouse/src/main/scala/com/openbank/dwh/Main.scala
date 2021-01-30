package com.openbank.dwh

import info.faljse.SDNotify.SDNotify
import com.openbank.dwh.boot._
import com.typesafe.scalalogging.StrictLogging
import scala.concurrent.{ExecutionContext, Await}
import scala.concurrent.duration._
import scala.sys

object Main extends App with StrictLogging {

  object Program
      extends ProgramLifecycle
      with ModulesLifecycle
      with GlobalConfigModule
      with MetricsModule
      with TypedActorModule
      with ServiceModule
      with PersistenceModule
      with RouterModule
      with AkkaModule
      with StrictLogging

  try {
    Await.result(Program.setup(), 10.minutes)
    sys.addShutdownHook {
      implicit val ec = ExecutionContext.global
      Program.shutdown().onComplete { _ =>
        SDNotify.sendStopping()
      }
      ()
    }
    SDNotify.sendNotify()
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
