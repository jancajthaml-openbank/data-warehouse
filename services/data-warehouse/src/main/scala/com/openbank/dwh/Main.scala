package com.openbank.dwh

import ch.qos.logback.classic.LoggerContext
import com.openbank.dwh.boot._
import com.openbank.dwh.support.Health
import com.typesafe.scalalogging.StrictLogging
import org.slf4j.LoggerFactory
import scala.concurrent.{Await, ExecutionContext, ExecutionContextExecutor}
import scala.concurrent.duration._
import scala.util.control.NonFatal

object Main extends App {

  val logger = LoggerFactory.getLogger(Main.getClass.getName)
  implicit val ec: ExecutionContextExecutor = ExecutionContext.global

  try {
    object Program
        extends StrictLogging
        with ProgramLifecycle
        with ProductionAkkaModule
        with ProductionConfigModule
        with ProductionMetricsModule
        with ProductionServiceModule
        with ProductionPersistenceModule
        with ProductionRouterModule
        with ProductionActorModule

    Await.result(Program.setup(), 10.minutes)
    sys.addShutdownHook {
      logger.info("Program Stopping")
      Await.result(Program.shutdown(), 10.minutes)
      logger.info("Program Stopped")
      LoggerFactory.getILoggerFactory match {
        case c: LoggerContext => c.stop()
        case _                => ()
      }
      Health.serviceStopping()
    }
    logger.info("Program Starting")
    Program.start().onComplete { _ =>
      logger.info("Program Started")
      Health.serviceReady()
    }
  } catch {
    case NonFatal(e) =>
      logger.error("Panic!", e)
      Health.serviceStopping()
      sys.exit(1)
  }
}
