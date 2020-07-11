package com.openbank.dwh.boot

import com.openbank.dwh.service._


trait ServiceModule {
  self: AkkaModule with PersistenceModule =>

  lazy val healthCheckService: HealthCheckService =
    new HealthCheckService(graphStorage)(defaultExecutionContext)

  lazy val primaryDataExplorationService: PrimaryDataExplorationService =
    new PrimaryDataExplorationService(primaryStorage, secondaryStorage)(dataExplorationExecutionContext, materializer)

  lazy val graphQLService: GraphQLService =
    new GraphQLService(graphStorage)(graphQLExecutionContext)

}

