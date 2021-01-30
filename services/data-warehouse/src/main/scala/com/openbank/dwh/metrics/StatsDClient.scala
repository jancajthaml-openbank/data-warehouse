package com.openbank.dwh.metrics

import com.timgroup.statsd.{NonBlockingStatsDClientBuilder, StatsDClient => JavaStatsDClient}

trait StatsDClient {
	def gauge(aspect: String, value: Long): Unit
	def count(aspect: String, value: Long): Unit
	def stop(): Unit
}

class StatsDClientImpl(private[this] val client: JavaStatsDClient)
	extends StatsDClient {

	def this(hostname: String, port: Int) = {
		this(
			new NonBlockingStatsDClientBuilder()
				.prefix("openbank.dwh")
				.hostname(hostname)
				.port(port)
				.enableTelemetry(false)
				.enableAggregation(true)
			    .aggregationFlushInterval(1000)
			    .aggregationShards(32)
				.build()
		)
	}

	override def gauge(aspect: String, value: Long): Unit =
		client.gauge(aspect, value)

	override def count(aspect: String, value: Long): Unit =
		client.count(aspect, value)

	override def stop(): Unit =
		client.stop()		
}