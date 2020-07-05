package com.openbank.dwh.persistence

import akka.Done
import com.typesafe.scalalogging.LazyLogging
import akka.stream.Materializer
import scala.concurrent.{ExecutionContext, Future}
import com.openbank.dwh.model._
import slick.jdbc.GetResult

// https://books.underscore.io/essential-slick/essential-slick-3.html


class SecondaryPersistence(persistence: Persistence)(implicit ec: ExecutionContext, implicit val mat: Materializer) extends LazyLogging {

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

    persistence
      .database
      .run(query)
      .map(_ => Done)
  }

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
        last_syn_event = EXCLUDED.last_syn_event;
    """

    persistence
      .database
      .run(query)
      .map(_ => Done)
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

    persistence
      .database
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
  private implicit def asTenant: GetResult[Tenant] = GetResult(r =>
    Tenant(
      name = r.nextString(),
      isPristine = true
    )
  )

}
