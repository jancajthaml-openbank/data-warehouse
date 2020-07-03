package com.openbank.dwh.service

import com.typesafe.scalalogging.LazyLogging
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try
import scala.util.control.NonFatal
import com.openbank.dwh.persistence.Persistence


class HealthCheckService(secondaryStorage: Persistence)(implicit ec: ExecutionContext) extends LazyLogging {

  import secondaryStorage.profile.api._

  def isHealthy: Future[Boolean] = {
    Future.fromTry(Try(secondaryStorage.database))
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
