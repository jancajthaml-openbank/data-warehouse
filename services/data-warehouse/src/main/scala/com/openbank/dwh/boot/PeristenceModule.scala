package com.openbank.dwh.boot

import akka.Done
import com.typesafe.scalalogging.StrictLogging
import scala.concurrent.Future
import scala.util.Try
import com.openbank.dwh.persistence._
import scala.util.control.NonFatal

trait PersistenceModule {

  def graphStorage: GraphQLPersistence

  def primaryStorage: PrimaryPersistence

  def secondaryStorage: SecondaryPersistence
}

trait ProductionPersistenceModule extends PersistenceModule with Lifecycle {
  self: AkkaModule with ConfigModule with StrictLogging =>

  abstract override def start(): Future[Done] = {
    super.start().map { _ =>
      logger.info("Starting Persistence Module")
      Done
    }
  }

  abstract override def stop(): Future[Done] = {
    logger.info("Stopping Persistence Module")

    Future
      .successful(Done)
      .flatMap(_ => Future.fromTry(Try(graphStorage.persistence.close())))
      .recover { case NonFatal(e) =>
        logger.error("Error closing graphql storage", e)
        Done
      }
      .flatMap(_ => Future.fromTry(Try(secondaryStorage.persistence.close())))
      .recover { case NonFatal(e) =>
        logger.error("Error closing secondary storage", e)
        Done
      }
      .flatMap { _ => super.stop() }
  }

  lazy val graphStorage: GraphQLPersistence =
    GraphQLPersistence.forConfig(config)

  lazy val primaryStorage: PrimaryPersistence =
    PrimaryPersistence.forConfig(config)

  lazy val secondaryStorage: SecondaryPersistence =
    SecondaryPersistence.forConfig(config)

}
