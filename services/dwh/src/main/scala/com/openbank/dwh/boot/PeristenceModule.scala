package com.openbank.dwh.boot

import akka.{Done, NotUsed}
import com.typesafe.scalalogging.LazyLogging
import scala.concurrent.Future
import akka.stream._
import akka.stream.scaladsl._
import akka.stream.Supervision.Decider
import scala.util.{Try, Success, Failure}
import scala.collection.immutable.Seq
import com.openbank.dwh.persistence.ConnectionProvider
import org.postgresql.PGConnection
import slick.jdbc.JdbcBackend.{DatabaseDef, Database}
import slick.jdbc.JdbcBackend
import com.openbank.dwh.persistence._
import scala.util.control.NonFatal


trait PersistenceModule extends Lifecycle {
  self: AkkaModule with ConfigModule with LazyLogging =>

  abstract override def setup(): Future[Done] = {
    super.setup().flatMap { _ =>
      logger.info("Starting Persistence Module")

      val provider = postgres.provider
      Future.fromTry(provider.acquire())
        .flatMap { _ =>
          provider.release(None)
          Future.successful(Done)
        }
    }
  }

  abstract override def stop(): Future[Done] = {
    Future.fromTry(Try {
      postgres.close()
      Done
    }).recover {
      case NonFatal(e) =>
        logger.error("Error closing persistence", e)
        Done
    }
    .flatMap { _ => super.stop() }
  }

  // FIXME also vertica and elastic

  lazy val postgres: Persistence =
    new Postgres(Database.forConfig("persistence-secondary.postgresql", config))

  lazy val primaryStorage: PrimaryPersistence =
    new PrimaryPersistence(config.getString("persistence-primary.storage"))(primaryExplorationExecutionContext, materializer)

}
