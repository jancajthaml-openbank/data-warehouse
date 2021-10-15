package com.openbank.dwh.boot

import com.openbank.dwh.service._

trait ServiceModule {

  def primaryDataExplorationService: PrimaryDataExplorationService

  def graphQLService: GraphQLService

  def healthCheckService: HealthCheckService
}

trait ProductionServiceModule extends ServiceModule {
  self: PersistenceModule with MetricsModule =>

  lazy val primaryDataExplorationService: PrimaryDataExplorationService =
    new PrimaryDataExplorationService(
      primaryStorage,
      secondaryStorage,
      metrics
    )

  lazy val graphQLService: GraphQLService =
    new GraphQLService(graphStorage)

  lazy val healthCheckService: HealthCheckService =
    new HealthCheckService(graphQLService)

}
