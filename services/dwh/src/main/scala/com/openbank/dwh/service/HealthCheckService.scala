package com.openbank.dwh.service

import com.typesafe.scalalogging.LazyLogging
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try
import scala.util.control.NonFatal
import com.openbank.dwh.persistence.Persistence


class HealthCheckService(postgres: Persistence)(implicit ec: ExecutionContext) extends LazyLogging {

  // FIXME add isVerticaHealthy

  // FIXME add isElasticHealthy

  def isPostgresHealthy: Future[Boolean] = {
    import postgres.profile.api._

    Future.fromTry(Try(postgres.database))
      .flatMap {
        _.run(sql"SELECT 1".as[Int]).map(_.contains(1))
      }
      .recover {
        case NonFatal(err) =>
          logger.error("Failed postgres health check", err)
          false
      }
  }

}
