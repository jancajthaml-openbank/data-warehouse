package com.openbank.dwh.boot

import akka.Done
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Route
import com.openbank.dwh.routers._
import com.typesafe.scalalogging.StrictLogging
import scala.concurrent.Future
import scala.util.Success

trait RouterModule extends Lifecycle {
  self: AkkaModule with ConfigModule with ServiceModule with StrictLogging =>

  private lazy val healthCheck = new HealthCheckRouter(healthCheckService).route
  private lazy val graphQL = new GraphQLRouter(graphQLService).route

  private lazy val bindToLocation = config.getString("http.service.bind-to")
  private lazy val bintToPort = config.getInt("http.service.port")

  def routes: Route = new RootRouter(healthCheck, graphQL).route

  abstract override def setup(): Future[Done] = {
    super.setup().flatMap { _ =>
      logger.info("Starting Router Module")

      Http()
        .newServerAt(bindToLocation, bintToPort)
        .bind(routes)
        .andThen {
          case Success(binding) =>
            logger.info(s"Listening on ${binding.localAddress}")
        }
        .map(_ => Done)
    }
  }

}
