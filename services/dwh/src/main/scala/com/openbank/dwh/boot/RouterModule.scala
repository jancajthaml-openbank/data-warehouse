package com.openbank.dwh.boot

import akka.Done
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Route
import com.openbank.dwh.routers._
import com.typesafe.scalalogging.StrictLogging
import akka.http.scaladsl.server.Directives._
import scala.concurrent.Future
import scala.util.Success


trait RouterModule extends Lifecycle {
  self: AkkaModule with ConfigModule with ServiceModule with StrictLogging =>

  private lazy val healthCheck = new HealthCheckRouter(healthCheckService).route

  def routes: Route = new RootRouter(healthCheck).route

  abstract override def start(): Future[Done] = {
    logger.info("starting Router Module")

    super.start().flatMap { _ =>
      Http()
        .bindAndHandle(routes,
                      config.getString("http.service.bind-to"),
                      config.getInt("http.service.port"))
        .andThen {
          case Success(binding) => logger.info(s"Listening on ${binding.localAddress}")
        }
        .map(_ => Done)
    }
  }
}