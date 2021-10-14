package com.openbank.dwh.metrics

import com.timgroup.statsd.{
  NonBlockingStatsDClientBuilder,
  StatsDClient => JavaStatsDClient
}
import com.typesafe.scalalogging.StrictLogging
import java.net.URI
import scala.util.control.NonFatal

trait StatsDClient {
  def gauge(aspect: String, value: Long): Unit

  def count(aspect: String, value: Long): Unit

  def start(uri: java.net.URI): StatsDClient

  def stop(): StatsDClient
}

object StatsDClient {

  private[metrics] case class LiveClient(private val client: JavaStatsDClient)
      extends StatsDClient {
    override def gauge(aspect: String, value: Long): Unit =
      client.gauge(aspect, value)

    override def count(aspect: String, value: Long): Unit =
      client.count(aspect, value)

    override def start(uri: URI): StatsDClient = this

    override def stop(): StatsDClient = {
      client.stop()
      NilClient
    }
  }

  private[metrics] case object NilClient extends StatsDClient {
    override def gauge(aspect: String, value: Long): Unit = {}

    override def count(aspect: String, value: Long): Unit = {}

    override def start(uri: URI): StatsDClient = {
      val client = new NonBlockingStatsDClientBuilder()
        .prefix("openbank.dwh")
        .hostname(uri.getHost())
        .port(uri.getPort())
        .enableTelemetry(false)
        .enableAggregation(true)
        .aggregationFlushInterval(1000)
        .aggregationShards(32)
        .build()
      LiveClient(client)
    }

    override def stop(): StatsDClient = this
  }
}

class StatsDClientImpl() extends StatsDClient with StrictLogging {

  private var client: StatsDClient = StatsDClient.NilClient
  private[this] val mutex = new Object()

  def start(uri: java.net.URI): StatsDClient = mutex.synchronized {
    try {
      client = client.start(uri)
      logger.info("Statsd Client connected to {}", uri)
    } catch {
      case NonFatal(e) =>
        logger.error("Failed to initialize Stats Client", e)
    }
    this
  }

  override def gauge(aspect: String, value: Long): Unit =
    client.gauge(aspect, value)

  override def count(aspect: String, value: Long): Unit =
    client.count(aspect, value)

  override def stop(): StatsDClient = mutex.synchronized {
    client = client.stop()
    logger.info("Stopping Statsd Client")
    this
  }
}
