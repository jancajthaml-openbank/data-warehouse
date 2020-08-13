package com.openbank.dwh.boot

import com.openbank.dwh.service._

trait ServiceModule {
  self: AkkaModule with PersistenceModule =>

  lazy val primaryDataExplorationService: PrimaryDataExplorationService =
    new PrimaryDataExplorationService(primaryStorage, secondaryStorage)(
      dataExplorationExecutionContext,
      materializer
    )

  lazy val graphQLService: GraphQLService =
    new GraphQLService(graphStorage)(graphQLExecutionContext)

  lazy val healthCheckService: HealthCheckService =
    new HealthCheckService(graphQLService)(graphQLExecutionContext)

}
