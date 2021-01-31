package com.openbank.dwh.boot

import com.openbank.dwh.metrics.{StatsDClient, StatsDClientImpl}
import com.typesafe.scalalogging.StrictLogging
import scala.concurrent.Future
import akka.Done
import scala.util.Try

trait MetricsModule extends Lifecycle {
  self: AkkaModule with ConfigModule with StrictLogging =>

  private var client: StatsDClient = null

  def metrics = client

  abstract override def setup(): Future[Done] = {
    super.setup().flatMap { _ =>
      Future
        .fromTry(Try {
          val uri =
            new java.net.URI(s"udp://${config.getString("statsd.endpoint")}")
          client = new StatsDClientImpl(
            uri.getHost(),
            uri.getPort()
          )
          logger.info(
            s"Starting Statsd Client on ${uri.getHost()}:${uri.getPort()}"
          )
        })
        .flatMap(_ => super.setup())
    }
  }

  abstract override def stop(): Future[Done] = {
    Future
      .successful(Done)
      .map {
        case _ if metrics != null =>
          logger.info("Stopping Statsd Client")
          client.stop()
          Done
        case _ =>
          Done
      }
      .flatMap(_ => super.stop())
  }

}
