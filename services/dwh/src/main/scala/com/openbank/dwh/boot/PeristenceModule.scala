package com.openbank.dwh.boot

import akka.{Done, NotUsed}
import com.typesafe.scalalogging.StrictLogging
import scala.concurrent.Future
import akka.stream._
import akka.stream.scaladsl._
import akka.stream.Supervision.Decider
import scala.util.{Try, Success, Failure}
import scala.collection.immutable.Seq
import com.openbank.dwh.persistence.ConnectionProvider
import org.postgresql.PGConnection
import slick.jdbc.JdbcBackend.DatabaseDef
import slick.jdbc.JdbcBackend
import com.openbank.dwh.persistence.Persistence
import scala.util.control.NonFatal


trait PersistenceModule extends Lifecycle {
  self: AkkaModule with ConfigModule with StrictLogging =>

  abstract override def stop(): Future[Done] = {
    Future.fromTry(Try {
      persistence.close()
      Done
    }).recover {
      case NonFatal(e) =>
        logger.error("Error closing persistence", e)
        Done
    }
    .flatMap { _ => super.stop() }
  }

  abstract override def start(): Future[Done] = {
    println("Starting Persistence Module")
    super.start().flatMap { _ =>
      val provider = persistence.provider
      Future.fromTry(provider.acquire())
        .flatMap { _ =>
          provider.release(None)
          Future.successful(Done)
        }
    }
  }

  lazy val persistence: Persistence = Persistence.forConfig(config)

}
