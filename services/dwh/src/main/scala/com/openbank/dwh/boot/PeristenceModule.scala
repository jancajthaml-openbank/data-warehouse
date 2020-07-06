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
        .fromTry(Try(postgres.close()))
        .map(_ => Done)
        .recover {
          case NonFatal(e) =>
            logger.error("Error closing database", e)
            Done
        }
    }
  }

  lazy val postgres: Persistence =
    Postgres.forConfig(config)

  lazy val secondaryStorage: SecondaryPersistence =
    new SecondaryPersistence(postgres)

  lazy val primaryStorage: PrimaryPersistence =
    PrimaryPersistence.forConfig(config, primaryExplorationExecutionContext, materializer)

}
