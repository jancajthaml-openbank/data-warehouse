package com.openbank.dwh.boot

import akka.Done
import com.typesafe.scalalogging.StrictLogging
import scala.concurrent.Future
import scala.util.Try
import slick.jdbc.JdbcBackend.Database
import com.openbank.dwh.persistence._
import scala.util.control.NonFatal

trait PersistenceModule extends Lifecycle {
  self: AkkaModule with TypedActorModule with ConfigModule with StrictLogging =>

  abstract override def stop(): Future[Done] = {
    super.stop().flatMap { _ =>
      Future
        .successful(Done)
        .flatMap(_ => Future.fromTry(Try(graphStorage.persistence.close())))
        .recover {
          case NonFatal(e) =>
            logger.error("Error closing graphql storage", e)
            Done
        }
        .flatMap(_ => Future.fromTry(Try(secondaryStorage.persistence.close())))
        .recover {
          case NonFatal(e) =>
            logger.error("Error closing secondary storage", e)
            Done
        }
        .map(_ => Done)
    }
  }

  lazy val graphStorage: GraphQLPersistence =
    GraphQLPersistence.forConfig(config, graphQLExecutionContext)

  lazy val primaryStorage: PrimaryPersistence =
    PrimaryPersistence.forConfig(
      config,
      dataExplorationExecutionContext,
      materializer
    )

  lazy val secondaryStorage: SecondaryPersistence =
    SecondaryPersistence.forConfig(
      config,
      dataExplorationExecutionContext,
      materializer
    )

}
