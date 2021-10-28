package com.openbank.dwh.boot

import akka.Done
import scala.concurrent.Future

trait Lifecycle {

  def setup(): Future[Done]

  def start(): Future[Done]

  def stop(): Future[Done]

}

trait ProgramLifecycle extends Lifecycle {

  override def setup(): Future[Done] =
    Future.successful(Done)

  override def start(): Future[Done] =
    Future.successful(Done)

  override def stop(): Future[Done] =
    Future.successful(Done)

}
