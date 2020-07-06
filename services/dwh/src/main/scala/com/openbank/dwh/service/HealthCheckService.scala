package com.openbank.dwh.service

import com.typesafe.scalalogging.LazyLogging
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try
import scala.util.control.NonFatal
import com.openbank.dwh.persistence.SecondaryPersistence


class HealthCheckService(secondaryPersistence: SecondaryPersistence)(implicit ec: ExecutionContext) extends LazyLogging {

  // FIXME add isElasticHealthy

  def isSecondaryStorageHealthy: Future[Boolean] = {
    import secondaryPersistence.profile.api._

    Future.fromTry(Try(secondaryPersistence.database))
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
