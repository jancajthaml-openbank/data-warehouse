package com.openbank.dwh.boot

import akka.Done
import scala.concurrent.Future


trait Lifecycle {

  def start(): Future[Done]

  def stop(): Future[Done]

}


class ProgramLifecycle extends Lifecycle {

  override def start(): Future[Done] = {
    Future.successful(Done)
  }

  override def stop(): Future[Done] = {
    Future.successful(Done)
  }

}

