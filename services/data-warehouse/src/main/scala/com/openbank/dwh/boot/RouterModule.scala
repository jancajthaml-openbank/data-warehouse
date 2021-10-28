package com.openbank.dwh.boot

import akka.Done
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Route
import com.openbank.dwh.routers._
import com.typesafe.scalalogging.StrictLogging
import scala.concurrent.Future
import scala.util.Success

trait RouterModule {

  def routes: Route

}

trait ProductionRouterModule extends RouterModule with Lifecycle {
  self: AkkaModule with ConfigModule with ServiceModule with StrictLogging =>

  private lazy val healthCheck = new HealthCheckRouter(healthCheckService).route
  private lazy val graphQL = new GraphQLRouter(graphQLService).route

  private lazy val bindToLocation = config.getString("http.service.bind-to")
  private lazy val bindToPort = config.getInt("http.service.port")

  lazy val routes: Route = new RootRouter(healthCheck, graphQL).route

  abstract override def start(): Future[Done] = {
    super.start().flatMap { _ =>
      logger.info("Starting Router Module")

      Http()
        .newServerAt(bindToLocation, bindToPort)
        .bind(routes)
        .andThen { case Success(binding) =>
          logger.info("REST Listening on tcp:/{}", binding.localAddress)
        }
        .map(_ => Done)
    }
  }

  abstract override def stop(): Future[Done] = {
    logger.info("Stopping Router Module")
    super.stop()
  }

}
