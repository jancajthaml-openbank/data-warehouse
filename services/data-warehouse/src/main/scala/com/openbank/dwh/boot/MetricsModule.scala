package com.openbank.dwh.boot

import com.openbank.dwh.metrics.{StatsDClient, StatsDClientImpl}

import scala.concurrent.Future
import akka.Done
import com.typesafe.scalalogging.StrictLogging

import scala.util.Try

trait MetricsModule {

  def metrics: StatsDClient

}

trait ProductionMetricsModule extends MetricsModule with Lifecycle {
  self: AkkaModule with ConfigModule with StrictLogging =>

  lazy val metrics = new StatsDClientImpl()

  abstract override def start(): Future[Done] = {
    super.start().flatMap { _ =>
			logger.info("Starting Metrics Module")
      Future
        .fromTry(Try {
          val uri =
            new java.net.URI(s"udp://${config.getString("statsd.endpoint")}")
          metrics.start(uri)
        })
        .map(_ => Done)
    }
  }

  abstract override def stop(): Future[Done] = {
		logger.info("Stopping Metrics Module")
		metrics.stop()
		super.stop()
  }

}
