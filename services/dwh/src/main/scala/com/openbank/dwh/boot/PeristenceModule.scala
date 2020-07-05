package com.openbank.dwh.boot

import akka.Done
import com.typesafe.scalalogging.LazyLogging
import scala.concurrent.Future
import scala.util.Try
import slick.jdbc.JdbcBackend.Database
import com.openbank.dwh.persistence._
import scala.util.control.NonFatal


trait PersistenceModule extends Lifecycle {
  self: AkkaModule with ConfigModule with LazyLogging =>

  abstract override def stop(): Future[Done] = {
    Future
      .fromTry(Try(postgres.close()))
      .map(_ => Done)
      .recover {
        case NonFatal(e) =>
          logger.error("Error closing database", e)
          Done
      }
      .flatMap(_ => super.stop())
  }

  lazy val postgres: Persistence =
    new Postgres(Database.forConfig("persistence-secondary.postgresql", config))

  lazy val secondaryStorage: SecondaryPersistence =
    new SecondaryPersistence(postgres)

  lazy val primaryStorage: PrimaryPersistence =
    new PrimaryPersistence(config.getString("persistence-primary.storage"))(primaryExplorationExecutionContext, materializer)

}
