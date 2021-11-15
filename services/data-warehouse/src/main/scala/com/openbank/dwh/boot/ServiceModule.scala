package com.openbank.dwh.boot

import com.openbank.dwh.service._
import akka.actor.typed.DispatcherSelector

trait ServiceModule {

  def primaryDataExplorationService: PrimaryDataExplorationService

  def graphQLService: GraphQLService

  def healthCheckService: HealthCheckService

}

trait ProductionServiceModule extends ServiceModule {
  self: AkkaModule with PersistenceModule with MetricsModule =>

  lazy val primaryDataExplorationService: PrimaryDataExplorationService =
    new PrimaryDataExplorationService(
      primaryStorage,
      secondaryStorage,
      metrics
    )(
      typedSystem.dispatchers.lookup(
        DispatcherSelector.fromConfig("data-exploration.dispatcher")
      ),
      materializer
    )

  lazy val graphQLService: GraphQLService =
    new GraphQLService(graphStorage)

  lazy val healthCheckService: HealthCheckService =
    new HealthCheckService(graphQLService)

}
