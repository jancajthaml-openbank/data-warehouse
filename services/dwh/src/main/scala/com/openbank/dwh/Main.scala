package com.openbank.dwh

import java.util.concurrent.TimeUnit
import com.openbank.dwh.boot._
import com.typesafe.scalalogging.{LazyLogging, StrictLogging}
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.sys

object Main extends App with LazyLogging {

  object Program
    extends ProgramLifecycle
    with ServiceLifecycle
    with GlobalConfigModule
    with AkkaModule
    with PersistenceModule
    with RouterModule
    with ServiceModule
    with StrictLogging

  try {
    Await.result(Program.start(), Duration(1, TimeUnit.MINUTES))
    sys.addShutdownHook { Program.shutdown() }
  } catch {
    case e: Exception =>
      e.printStackTrace()
      Program.stop()
  }
}
