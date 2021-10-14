package com.openbank.dwh.service

import com.typesafe.scalalogging.StrictLogging
import scala.concurrent.{ExecutionContext, Future}

class HealthCheckService(graphQL: GraphQLService) extends StrictLogging {

  def isGraphQLHealthy()(implicit ec: ExecutionContext): Future[Boolean] = {
    val query = "query { tenants(limit: 1, offset: 0) { name } }"

    graphQL
      .execute(query, None)
      .map(_.asJsObject.getFields("data") match {
        case Seq(_) => true
        case _      => false
      })
      .recover { case _: Exception => false }
  }

}
