package com.openbank.dwh.service

import com.typesafe.scalalogging.StrictLogging
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try
import scala.util.control.NonFatal
import com.openbank.dwh.persistence.Persistence


class HealthCheckService(persistence: Persistence)(implicit ec: ExecutionContext) extends StrictLogging {

  import persistence.profile.api._

  def isHealthy: Future[Boolean] = {
    Future.fromTry(Try(persistence.database))
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
