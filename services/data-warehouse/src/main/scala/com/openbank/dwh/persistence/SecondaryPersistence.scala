package com.openbank.dwh.persistence

import com.typesafe.config.Config
import akka.Done
import com.typesafe.scalalogging.StrictLogging
import akka.stream.Materializer
import scala.concurrent.{ExecutionContext, Future}
import com.openbank.dwh.model._
import scala.math.BigDecimal
import java.time.{ZonedDateTime, ZoneOffset}
import slick.jdbc._
import slick.jdbc.JdbcBackend.Database
import java.sql.{Timestamp, Types}
import slick.basic.DatabasePublisher
import akka.stream._
import akka.stream.scaladsl._

object SecondaryPersistence {

  def forConfig(
      config: Config,
      ec: ExecutionContext,
      mat: Materializer
  ): SecondaryPersistence = {
    new SecondaryPersistence(
      Postgres.forConfig(config, "data-exploration.postgresql")
    )(ec, mat)
  }

}

// FIXME split into interface and impl for better testing
class SecondaryPersistence(val persistence: Postgres)(
    implicit ec: ExecutionContext,
    implicit val mat: Materializer
) extends StrictLogging {

  import persistence.profile.api._

  def updateTenant(item: PersistentTenant): Future[Done] = {

    val query = sqlu"""
      INSERT INTO
        tenant(name)
      VALUES
        (${item.name})
      ON CONFLICT (name)
      DO NOTHING
      ;
    """

    persistence.database
      .run(query)
      .map { _ =>
        logger.debug(s"update tenant ${item}")
        Done
      }
      .recoverWith {
        case e: Exception =>
          logger.error(s"failed to update tenant", e)
          Future.failed(e)
      }
  }

  def updateAccount(item: PersistentAccount): Future[Done] = {

    val query = sqlu"""
      INSERT INTO
        account(tenant, name, format, currency, last_syn_snapshot, last_syn_event)
      VALUES
        (${item.tenant}, ${item.name}, ${item.format}, ${item.currency}, ${item.lastSynchronizedSnapshot}, ${item.lastSynchronizedEvent})
      ON CONFLICT (tenant, name)
      DO UPDATE
      SET
        format = EXCLUDED.format,
        currency = EXCLUDED.currency,
        last_syn_snapshot = EXCLUDED.last_syn_snapshot,
        last_syn_event = EXCLUDED.last_syn_event,
        updated_at = NOW()
      ;
    """

    persistence.database
      .run(query)
      .map { _ =>
        logger.debug(s"update account ${item}")
        Done
      }
      .recoverWith {
        case e: Exception =>
          logger.error(s"failed to update account", e)
          Future.failed(e)
      }
  }

  def updateTransfer(item: PersistentTransfer): Future[Done] = {

    val query = sqlu"""
      INSERT INTO
        transfer(tenant, transaction, transfer, status, credit_tenant, credit_name, debit_tenant, debit_name, amount, currency, value_date)
      VALUES
        (${item.tenant}, ${item.transaction}, ${item.transfer}, ${item.status}, ${item.creditTenant}, ${item.creditAccount}, ${item.debitTenant}, ${item.debitAccount}, ${item.amount}, ${item.currency}, ${Timestamp
      .valueOf(
        item.valueDate.withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime
      )})
      ON CONFLICT (tenant, transaction, transfer)
      DO NOTHING
      ;
    """

    persistence.database
      .run(query)
      .map { _ =>
        logger.debug(s"update transfer ${item}")
        Done
      }
      .recoverWith {
        case e: Exception =>
          logger.error(s"failed to update transfer", e)
          Future.failed(e)
      }
  }

  def getTransfer(
      tenant: String,
      transaction: String,
      transfer: String
  ): Future[Option[PersistentTransfer]] = {

    val query = sql"""
      SELECT
        tenant,
        transaction,
        transfer,
        credit_tenant,
        credit_name,
        debit_name,
        debit_tenant,
        amount,
        currency,
        value_date
      FROM
        transfer
      WHERE
        tenant = ${tenant} AND
        transaction = ${transaction} AND
        transfer = ${transfer}
      ;
    """.as[PersistentTransfer]

    persistence.database
      .run(
        query
          .withStatementParameters(
            rsType = ResultSetType.ForwardOnly,
            rsConcurrency = ResultSetConcurrency.ReadOnly,
            fetchSize = 1
          )
      )
      .map(_.headOption)
  }

  def getAccount(
      tenant: String,
      name: String
  ): Future[Option[PersistentAccount]] = {

    val query = sql"""
      SELECT
        tenant,
        name,
        format,
        currency,
        last_syn_snapshot,
        last_syn_event
      FROM
        account
      WHERE
        tenant = ${tenant} AND
        name = ${name}
      ;
    """.as[PersistentAccount]

    persistence.database
      .run(
        query
          .withStatementParameters(
            rsType = ResultSetType.ForwardOnly,
            rsConcurrency = ResultSetConcurrency.ReadOnly,
            fetchSize = 1
          )
      )
      .map(_.headOption)
  }

  def getTenant(name: String): Future[Option[PersistentTenant]] = {

    val query = sql"""
      SELECT
        name
      FROM
        tenant
      WHERE
        name = ${name}
      ;
    """.as[PersistentTenant]

    persistence.database
      .run(
        query
          .withStatementParameters(
            rsType = ResultSetType.ForwardOnly,
            rsConcurrency = ResultSetConcurrency.ReadOnly,
            fetchSize = 1
          )
      )
      .map(_.headOption)
  }

  private implicit def asAccount: GetResult[PersistentAccount] =
    GetResult(r =>
      PersistentAccount(
        tenant = r.nextString(),
        name = r.nextString(),
        format = r.nextString(),
        currency = r.nextString(),
        lastSynchronizedSnapshot = r.nextInt(),
        lastSynchronizedEvent = r.nextInt()
      )
    )

  private implicit def asTransfer: GetResult[PersistentTransfer] =
    GetResult(r =>
      PersistentTransfer(
        tenant = r.nextString(),
        transaction = r.nextString(),
        transfer = r.nextString(),
        status = r.nextInt(),
        creditTenant = r.nextString(),
        creditAccount = r.nextString(),
        debitTenant = r.nextString(),
        debitAccount = r.nextString(),
        amount = r.nextBigDecimal(),
        currency = r.nextString(),
        valueDate =
          ZonedDateTime.ofInstant(r.nextTimestamp().toInstant(), ZoneOffset.UTC)
      )
    )

  private implicit def asTenant: GetResult[PersistentTenant] =
    GetResult(r =>
      PersistentTenant(
        name = r.nextString()
      )
    )

}
