package com.openbank.dwh.service

import com.typesafe.scalalogging.LazyLogging
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try
import scala.util.control.NonFatal
import com.openbank.dwh.persistence.Persistence


class HealthCheckService(postgres: Persistence)(implicit ec: ExecutionContext) extends LazyLogging {

  import postgres.profile.api._

  def isHealthy: Future[Boolean] = {
    Future.fromTry(Try(postgres.database))
      .flatMap {
        _.run(sql"SELECT 1".as[Int]).map(_.contains(1))
      }
      .recover {
        case NonFatal(err) =>
          logger.error("Failed DB health check", err)
          false
      }
  }

}
