package com.openbank.dwh.persistence

import com.typesafe.config.Config
import akka.Done
import com.typesafe.scalalogging.LazyLogging
import akka.stream.Materializer
import scala.concurrent.{ExecutionContext, Future}
import com.openbank.dwh.model._
import scala.math.BigDecimal
import java.time.{ZonedDateTime, ZoneOffset}
import slick.jdbc.{JdbcProfile, GetResult, SetParameter}
import slick.jdbc.JdbcBackend.Database
import java.sql.{Timestamp, Types}


object SecondaryPersistence {

  def forConfig(config: Config, ec: ExecutionContext, mat: Materializer): SecondaryPersistence = {
    new SecondaryPersistence(Postgres.forConfig(config))(ec, mat)
  }

}


// FIXME split into interface and impl for better testing
class SecondaryPersistence(persistence: Persistence)(implicit ec: ExecutionContext, implicit val mat: Materializer) extends Persistence with LazyLogging {

  def updateTenant(item: Tenant): Future[Done] = {
    import persistence.profile.api._

    val query = sqlu"""
      INSERT INTO
        tenant(name)
      VALUES
        (${item.name})
      ON CONFLICT (name)
      DO NOTHING;
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

  // FIXME problem with future that the last_syn_snapshot, last_syn_event are not processed in order
  def updateAccount(item: Account): Future[Done] = {
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
        updated_at = NOW();
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

  def updateTransfer(item: Transfer): Future[Done] = {
    import persistence.profile.api._

    val query = sqlu"""
      INSERT INTO
        transfer(tenant, transaction, transfer, credit_tenant, credit_name, debit_tenant, debit_name, amount, currency, value_date)
      VALUES
        (${item.tenant}, ${item.transaction}, ${item.transfer}, ${item.creditTenant}, ${item.creditAccount}, ${item.debitTenant}, ${item.debitAccount}, ${item.amount}, ${item.currency}, ${Timestamp.valueOf(item.valueDate.withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime)})
      ON CONFLICT (tenant, transaction, transfer) DO NOTHING;
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

  def getTransfer(tenant: String, transaction: String, transfer: String): Future[Option[Transfer]] = {
    import persistence.profile.api._

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

    database
      .run(query)
      .map(_.headOption)
  }

  def getAccount(tenant: String, name: String): Future[Option[Account]] = {
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
        name = ${name};
    """.as[Account]

    database
      .run(query)
      .map(_.headOption)
  }

  def getTenant(name: String): Future[Option[Tenant]] = {
    import persistence.profile.api._

    val query = sql"""
      SELECT
        name
      FROM
        tenant
      WHERE
        name = ${name};
    """.as[Tenant]

    database
      .run(query)
      .map(_.headOption)
  }

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

  private implicit def asTransfer: GetResult[Transfer] = GetResult(r =>
    Transfer(
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

  private implicit def asTenant: GetResult[Tenant] = GetResult(r =>
    Tenant(
      name = r.nextString(),
      isPristine = true
    )
  )

  /*
  // FIXME there implicit do not work now

  private implicit val SetZonedDateTime = SetParameter[ZonedDateTime] { (v, params) =>
    params.setTimestamp(Timestamp.valueOf(v.withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime))
  }

  */

  override val database: Database = persistence.database

  override val profile: JdbcProfile = persistence.profile

  override def close(): Unit = persistence.close()

}
