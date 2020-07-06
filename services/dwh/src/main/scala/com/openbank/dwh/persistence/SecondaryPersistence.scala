package com.openbank.dwh.persistence

import akka.Done
import com.typesafe.scalalogging.LazyLogging
import akka.stream.Materializer
import scala.concurrent.{ExecutionContext, Future}
import com.openbank.dwh.model._
import slick.jdbc.GetResult
import scala.math.BigDecimal

// https://books.underscore.io/essential-slick/essential-slick-3.html

// FIXME split into interface and impl for better testing
class SecondaryPersistence(persistence: Persistence)(implicit ec: ExecutionContext, implicit val mat: Materializer) extends LazyLogging {

  def updateTenant(item: Tenant): Future[Done] = {
    import persistence.profile.api._

    // FIXME statement is now prepared JIT with each call and that costs 1ms, prepare statement out of scope of this function
    val query = sqlu"""
      INSERT INTO
        tenant(name)
      VALUES
        (${item.name})
      ON CONFLICT (name)
      DO NOTHING;
    """

    persistence
      .database
      .run(query)
      .map(_ => Done)
      .recoverWith {
        case e: Exception =>
          logger.error(s"failed to upsert tenant", e)
          Future.failed(e)
      }
  }

  // FIXME problem with future that the last_syn_snapshot, last_syn_event are not processed in order
  def updateAccount(item: Account): Future[Done] = {
    import persistence.profile.api._

    // FIXME statement is now prepared JIT with each call and that costs 1ms, prepare statement out of scope of this function
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
        last_syn_event = EXCLUDED.last_syn_event;
    """

    persistence
      .database
      .run(query)
      .map(_ => Done)
      .recoverWith {
        case e: Exception =>
          logger.error(s"failed to upsert account", e)
          Future.failed(e)
      }
  }

  def updateTransfer(item: Transfer): Future[Done] = {
    import persistence.profile.api._

    // FIXME statement is now prepared JIT with each call and that costs 1ms, prepare statement out of scope of this function
    val query = sqlu"""
      INSERT INTO
        transfer(tenant, transaction, transfer, status, credit_tenant, credit_name, debit_tenant, debit_name, amount, currency, value_date)
      VALUES
        (${item.tenant}, ${item.transaction}, ${item.transfer}, ${item.status}, ${item.creditTenant}, ${item.creditAccount}, ${item.debitTenant}, ${item.debitAccount}, ${item.amount}, ${item.currency}, ${item.valueDate})
      ON CONFLICT (tenant, transaction, transfer)
      DO UPDATE
      SET
        status = EXCLUDED.status;
    """

    persistence
      .database
      .run(query)
      .map(_ => Done)
      .recoverWith {
        case e: Exception =>
          logger.error(s"failed to upsert transfer", e)
          Future.failed(e)
      }
  }

  def getTransfer(tenant: String, transaction: String, transfer: String): Future[Option[Transfer]] = {
    import persistence.profile.api._

    // FIXME statement is now prepared JIT with each call and that costs 1ms, prepare statement out of scope of this function
    val query = sql"""
      SELECT
        tenant,
        transaction,
        transfer,
        status,
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
        transfer = ${transfer};
    """.as[Transfer]

    persistence
      .database
      .run(query)
      .map(_.headOption)
  }

  def getAccount(tenant: String, name: String): Future[Option[Account]] = {
    import persistence.profile.api._

    // FIXME statement is now prepared JIT with each call and that costs 1ms, prepare statement out of scope of this function
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
        name = ${name};
    """.as[Account]

    persistence
      .database
      .run(query)
      .map(_.headOption)
  }

  def getTenant(name: String): Future[Option[Tenant]] = {
    import persistence.profile.api._

    // FIXME statement is now prepared JIT with each call and that costs 1ms, prepare statement out of scope of this function
    val query = sql"""
      SELECT
        name
      FROM
        tenant
      WHERE
        name = ${name};
    """.as[Tenant]

    persistence
      .database
      .run(query)
      .map(_.headOption)
  }

  @SuppressWarnings(Array("scala:S1144"))
  private implicit def asAccount: GetResult[Account] = GetResult(r =>
    Account(
      tenant = r.nextString(),
      name = r.nextString(),
      format = r.nextString(),
      currency = r.nextString(),
      lastSynchronizedSnapshot = r.nextInt(),
      lastSynchronizedEvent = r.nextInt(),
      isPristine = true
    )
  )

  @SuppressWarnings(Array("scala:S1144"))
  private implicit def asTransfer: GetResult[Transfer] = GetResult(r =>
    Transfer(
      tenant = r.nextString(),
      transaction = r.nextString(),
      transfer = r.nextString(),
      status = r.nextString(),
      creditTenant = r.nextString(),
      creditAccount = r.nextString(),
      debitTenant = r.nextString(),
      debitAccount = r.nextString(),
      amount = r.nextBigDecimal(),
      currency = r.nextString(),
      valueDate = r.nextString(),
      isPristine =true
    )
  )

  @SuppressWarnings(Array("scala:S1144"))
  private implicit def asTenant: GetResult[Tenant] = GetResult(r =>
    Tenant(
      name = r.nextString(),
      isPristine = true
    )
  )

}
