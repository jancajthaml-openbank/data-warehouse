package com.openbank.dwh

import com.openbank.dwh.boot._
import com.openbank.dwh.support.Health
import com.typesafe.scalalogging.StrictLogging
import scala.concurrent.{Await, ExecutionContext, ExecutionContextExecutor}
import scala.concurrent.duration._
import scala.util.control.NonFatal

object Main extends App {

  object Program
      extends StrictLogging
      with ProgramLifecycle
      with ProductionAkkaModule
      with ProductionConfigModule
      with ProductionMetricsModule
      with ProductionServiceModule
      with ProductionPersistenceModule
      with ProductionRouterModule
      with TypedActorModule

  try {
    Await.result(Program.setup(), 10.minutes)
    sys.addShutdownHook {
      implicit val ec: ExecutionContextExecutor = ExecutionContext.global
      Program.shutdown().onComplete { _ =>
        Health.serviceStopping()
      }
    }
    Health.serviceReady()
    Program.start()
  } catch {
    case NonFatal(e) =>
      implicit val ec: ExecutionContext = ExecutionContext.global
      e.printStackTrace()
      Program
        .stop()
        .onComplete { _ => System.exit(1) }
  }
}
