package com.openbank.dwh.boot

import akka.Done
import scala.concurrent.Future
import com.typesafe.scalalogging.LazyLogging


trait Lifecycle {

  def setup(): Future[Done]

  def start(): Future[Done]

  def stop(): Future[Done]

}


class ProgramLifecycle extends Lifecycle {
  self: LazyLogging =>

  override def setup(): Future[Done] = {
    logger.info("Program Starting")
    Future.successful(Done)
  }

  override def start(): Future[Done] = {
    Future.successful(Done)
  }

  override def stop(): Future[Done] = {
    logger.info("Program Stopping")
    Future.successful(Done)
  }

}

