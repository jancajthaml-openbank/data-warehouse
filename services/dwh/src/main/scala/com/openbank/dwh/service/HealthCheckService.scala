package com.openbank.dwh.service

import com.typesafe.scalalogging.StrictLogging
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try
import scala.util.control.NonFatal
import com.openbank.dwh.persistence.GraphQLPersistence


class HealthCheckService(graphqlPersistence: GraphQLPersistence)(implicit ec: ExecutionContext) extends StrictLogging {

  def isSecondaryStorageHealthy: Future[Boolean] = {
    import graphqlPersistence.persistence.profile.api._

    Future
      .fromTry(Try(graphqlPersistence.persistence.database))
      .flatMap {
        _.run(sql"SELECT 1".as[Int]).map(_.contains(1))
      }
      .recover {
        case NonFatal(err) =>
          logger.error("Failed secondary storage health check", err)
          false
      }
  }

}
