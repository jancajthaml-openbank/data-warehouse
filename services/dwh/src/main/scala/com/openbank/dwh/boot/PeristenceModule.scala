package com.openbank.dwh.boot

import akka.Done
import com.typesafe.scalalogging.LazyLogging
import scala.concurrent.Future
import scala.util.Try
import slick.jdbc.JdbcBackend.Database
import com.openbank.dwh.persistence._
import scala.util.control.NonFatal


trait PersistenceModule extends Lifecycle {
  self: AkkaModule with TypedActorModule with ConfigModule with LazyLogging =>

  abstract override def stop(): Future[Done] = {
    super.stop().flatMap { _ =>
      Future
        .fromTry(Try(secondaryStorage.close()))
        .map(_ => Done)
        .recover {
          case NonFatal(e) =>
            logger.error("Error closing secondary storage", e)
            Done
        }
    }
  }

  lazy val secondaryStorage: SecondaryPersistence =
    SecondaryPersistence.forConfig(config, defaultExecutionContext, materializer)

  lazy val primaryStorage: PrimaryPersistence =
    PrimaryPersistence.forConfig(config, primaryExplorationExecutionContext, materializer)

}
