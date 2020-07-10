package com.openbank.dwh.persistence

import com.typesafe.config.Config
import akka.Done
import com.typesafe.scalalogging.LazyLogging
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

  def forConfig(config: Config, ec: ExecutionContext, mat: Materializer): SecondaryPersistence =
    new SecondaryPersistence(Postgres.forConfig(config))(ec, mat)

}


// FIXME split into interface and impl for better testing
class SecondaryPersistence(persistence: Persistence)(implicit ec: ExecutionContext, implicit val mat: Materializer) extends Persistence with LazyLogging {

  def updateTenant(item: PersistentTenant): Future[Done] = {
    import persistence.profile.api._

    val query = sqlu"""
      INSERT INTO
        tenant(name)
      VALUES
        (${item.name})
      ON CONFLICT (name)
      DO NOTHING
      ;
    """

    database
      .run(query)
      .map(_ => Done)
      .recoverWith {
        case e: Exception =>
          logger.error(s"failed to upsert tenant", e)
          Future.failed(e)
      }
  }

  def updateAccount(item: PersistentAccount): Future[Done] = {
    import persistence.profile.api._

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

    database
      .run(query)
      .map(_ => Done)
      .recoverWith {
        case e: Exception =>
          logger.error(s"failed to upsert account", e)
          Future.failed(e)
      }
  }

  def updateTransfer(item: PersistentTransfer): Future[Done] = {
    import persistence.profile.api._

    val query = sqlu"""
      INSERT INTO
        transfer(tenant, transaction, transfer, credit_tenant, credit_name, debit_tenant, debit_name, amount, currency, value_date)
      VALUES
        (${item.tenant}, ${item.transaction}, ${item.transfer}, ${item.creditTenant}, ${item.creditAccount}, ${item.debitTenant}, ${item.debitAccount}, ${item.amount}, ${item.currency}, ${Timestamp.valueOf(item.valueDate.withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime)})
      ON CONFLICT (tenant, transaction, transfer)
      DO NOTHING
      ;
    """

    database
      .run(query)
      .map(_ => Done)
      .recoverWith {
        case e: Exception =>
          logger.error(s"failed to upsert transfer", e)
          Future.failed(e)
      }
  }

  def getTransfer(tenant: String, transaction: String, transfer: String): Future[Option[PersistentTransfer]] = {
    import persistence.profile.api._

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

    database.run(
      query
        .withStatementParameters(
          rsType = ResultSetType.ForwardOnly,
          rsConcurrency = ResultSetConcurrency.ReadOnly,
          fetchSize = 1
        )
    )
    .map(_.headOption)
  }

  def getTransfers(tenant: String): DatabasePublisher[PersistentTransfer] = {
    import persistence.profile.api._

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
        tenant = ${tenant}
      ORDER BY
        tenant ASC,
        transaction ASC,
        transfer ASC
      ;
    """.as[PersistentTransfer]

    database.stream(
      query
        .withStatementParameters(
          rsType = ResultSetType.ForwardOnly,
          rsConcurrency = ResultSetConcurrency.ReadOnly,
          fetchSize = 100
        )
        .transactionally
    )
  }

  // FIXME should not be here
  def getTransfersAsFuture(tenant: String): Future[Seq[PersistentTransfer]] =
    Source.fromPublisher(getTransfers(tenant)).runWith(Sink.seq)

  def getAccount(tenant: String, name: String): Future[Option[PersistentAccount]] = {
    import persistence.profile.api._

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

    database.run(
      query
        .withStatementParameters(
          rsType = ResultSetType.ForwardOnly,
          rsConcurrency = ResultSetConcurrency.ReadOnly,
          fetchSize = 1
        )
    )
    .map(_.headOption)
  }

  def getAccounts(tenant: String): DatabasePublisher[PersistentAccount] = {
    import persistence.profile.api._

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
        tenant = ${tenant}
      ORDER BY
        name ASC
      ;
    """.as[PersistentAccount]

    database.stream(
      query
        .withStatementParameters(
          rsType = ResultSetType.ForwardOnly,
          rsConcurrency = ResultSetConcurrency.ReadOnly,
          fetchSize = 100
        )
        .transactionally
    )
  }

  // FIXME should not be here
  def getAccountsAsFuture(tenant: String): Future[Seq[PersistentAccount]] =
    Source.fromPublisher(getAccounts(tenant)).runWith(Sink.seq)

  def getTenant(name: String): Future[Option[PersistentTenant]] = {
    import persistence.profile.api._

    val query = sql"""
      SELECT
        name
      FROM
        tenant
      WHERE
        name = ${name}
      ;
    """.as[PersistentTenant]

    database.run(
      query
        .withStatementParameters(
          rsType = ResultSetType.ForwardOnly,
          rsConcurrency = ResultSetConcurrency.ReadOnly,
          fetchSize = 1
        )
    )
    .map(_.headOption)
  }

  def getTenants(): DatabasePublisher[PersistentTenant] = {
    import persistence.profile.api._

    val query = sql"""
      SELECT
        name
      FROM
        tenant
      ORDER BY
        name ASC
      ;
    """.as[PersistentTenant]

    database.stream(
      query
        .withStatementParameters(
          rsType = ResultSetType.ForwardOnly,
          rsConcurrency = ResultSetConcurrency.ReadOnly,
          fetchSize = 10
        )
        .transactionally
    )
  }

  // FIXME should not be here
  def getTenantsAsFuture(): Future[Seq[PersistentTenant]] =
    Source.fromPublisher(getTenants()).runWith(Sink.seq)

  private implicit def asAccount: GetResult[PersistentAccount] = GetResult(r =>
    PersistentAccount(
      tenant = r.nextString(),
      name = r.nextString(),
      format = r.nextString(),
      currency = r.nextString(),
      lastSynchronizedSnapshot = r.nextInt(),
      lastSynchronizedEvent = r.nextInt()
    )
  )

  private implicit def asTransfer: GetResult[PersistentTransfer] = GetResult(r =>
    PersistentTransfer(
      tenant = r.nextString(),
      transaction = r.nextString(),
      transfer = r.nextString(),
      creditTenant = r.nextString(),
      creditAccount = r.nextString(),
      debitTenant = r.nextString(),
      debitAccount = r.nextString(),
      amount = r.nextBigDecimal(),
      currency = r.nextString(),
      valueDate = ZonedDateTime.ofInstant(r.nextTimestamp().toInstant(), ZoneOffset.UTC),
      isPristine = true
    )
  )

  private implicit def asTenant: GetResult[PersistentTenant] = GetResult(r =>
    PersistentTenant(
      name = r.nextString()
    )
  )

  override val database: Database = persistence.database

  override val profile: JdbcProfile = persistence.profile

  override def close(): Unit = persistence.close()

}
