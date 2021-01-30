package com.openbank.dwh.boot

import com.openbank.dwh.service._

trait ServiceModule {
  self: AkkaModule with PersistenceModule with MetricsModule =>

  lazy val primaryDataExplorationService: PrimaryDataExplorationService =
    new PrimaryDataExplorationService(primaryStorage, secondaryStorage, metrics)(
      dataExplorationExecutionContext,
      materializer
    )

  lazy val graphQLService: GraphQLService =
    new GraphQLService(graphStorage)(graphQLExecutionContext)

  lazy val healthCheckService: HealthCheckService =
    new HealthCheckService(graphQLService)(graphQLExecutionContext)

}
