package com.openbank.dwh.boot

import akka.Done
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Route
import com.openbank.dwh.routers._
import com.typesafe.scalalogging.LazyLogging
import akka.http.scaladsl.server.Directives._
import scala.concurrent.Future
import scala.util.Success


trait RouterModule extends Lifecycle {
  self: AkkaModule with ConfigModule with ServiceModule with LazyLogging =>

  private lazy val healthCheck = new HealthCheckRouter(healthCheckService).route

  def routes: Route = new RootRouter(healthCheck).route

  abstract override def setup(): Future[Done] = {
    super.setup().flatMap { _ =>
      logger.info("Starting Router Module")

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
