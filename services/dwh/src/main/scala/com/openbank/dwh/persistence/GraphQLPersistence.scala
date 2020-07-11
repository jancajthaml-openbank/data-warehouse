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
import slick.basic.DatabasePublisher
import akka.stream._
import akka.stream.scaladsl._
import java.sql.Timestamp
import akka.http.scaladsl.model.DateTime


object GraphQLPersistence {

  def forConfig(config: Config, ec: ExecutionContext, mat: Materializer): GraphQLPersistence =
    new GraphQLPersistence(Postgres.forConfig(config, "graphql.postgresql"))(ec, mat)

}


// FIXME split into interface and impl for better testing
class GraphQLPersistence(val persistence: Postgres)(implicit ec: ExecutionContext, implicit val mat: Materializer) extends StrictLogging {

  import persistence.profile.api._

  implicit val dateTimeColumnType = MappedColumnType.base[DateTime, Timestamp](
    dt => new Timestamp(dt.clicks),
    ts => DateTime(ts.getTime)
  )

  class TenantTable(tag: Tag) extends Table[Tenant](tag, "tenant") {
    def name = column[String]("name")
    def * = (name) <> (Tenant.apply, Tenant.unapply)
    def pk = primaryKey("tenant_pkey", name)
  }

  val Tenants = TableQuery[TenantTable]

  class AccountTable(tag: Tag) extends Table[Account](tag, "account") {
    def tenant = column[String]("tenant")
    def name = column[String]("name")
    def currency = column[String]("currency")
    def format = column[String]("format")
    def * = (tenant, name, currency, format) <> ((Account.apply _).tupled, Account.unapply)
    def pk = primaryKey("account_pkey", (tenant, name))
    def tenant_fk = foreignKey("account_tenant_fkey", tenant, Tenants)(_.name)
  }

  val Accounts = TableQuery[AccountTable]

  class TransferTable(tag: Tag) extends Table[Transfer](tag, "transfer") {
    def tenant = column[String]("tenant")
    def transaction = column[String]("transaction")
    def transfer = column[String]("transfer")
    def creditTenant = column[String]("credit_tenant")
    def creditAccount = column[String]("credit_name")
    def debitTenant = column[String]("debit_tenant")
    def debitAccount = column[String]("debit_name")
    def currency = column[String]("currency")
    def amount = column[BigDecimal]("amount")
    def valueDate = column[DateTime]("value_date")
    def * = (tenant, transaction, transfer, creditTenant, creditAccount, debitTenant, debitAccount, amount, currency, valueDate) <> ((Transfer.apply _).tupled, Transfer.unapply)
    def pk = primaryKey("transfer_pkey", (tenant, transaction, transfer))
    def tenant_fk = foreignKey("transfer_tenant_fkey", tenant, Tenants)(_.name)
    def credit_fk = foreignKey("transfer_credit_tenant_credit_name_fkey", (creditTenant, creditAccount), Accounts) { account => (account.tenant, account.name) }
    def debit_fk = foreignKey("transfer_debit_tenant_debit_name_fkey", (debitTenant, debitAccount), Accounts) { account => (account.tenant, account.name) }
  }

  val Transfers = TableQuery[TransferTable]

  lazy val allTenants = {
    val query = Compiled {
      (limit: ConstColumn[Long], offset: ConstColumn[Long]) =>
        Tenants
          .sortBy(_.name)
          .drop(offset)
          .take(limit)
    }

    (limit: Long, offset: Long) => persistence.database.run {
      query(limit, offset).result
    }
  }

  lazy val tenants = {
    val query = Compiled {
      (names: Rep[List[String]]) =>
        Tenants
          .filter { row => row.name === names.any }
    }

    (names: List[String]) => persistence.database.run {
      query(names).result
    }
  }

  lazy val tenant = {
    val query = Compiled {
      (name: Rep[String]) => Tenants
        .filter { row => row.name === name }
    }

    (name: String) => persistence.database.run {
      query(name).result.headOption
    }
  }

  lazy val allAccounts = {
    val query = Compiled {
      (tenant: Rep[String], limit: ConstColumn[Long], offset: ConstColumn[Long]) =>
        Accounts
          .filter { row => row.tenant === tenant }
          .sortBy(_.name)
          .drop(offset)
          .take(limit)
    }

    (tenant: String, limit: Long, offset: Long) => persistence.database.run {
      query(tenant, limit, offset).result
    }
  }

  lazy val accounts = {
    val query = Compiled {
      (tenant: Rep[String], names: Rep[List[String]]) =>
        Accounts
          .filter { row => row.tenant === tenant }
          .filter { row => row.name === names.any }
    }

    (tenant: String, names: List[String]) => persistence.database.run {
      query(tenant, names).result
    }
  }

  lazy val account = {
    val query = Compiled {
      (tenant: Rep[String], name: Rep[String]) =>
        Accounts
          .filter { row => row.tenant === tenant }
          .filter { row => row.name === name }
    }

    (tenant: String, name: String) => persistence.database.run {
      query(tenant, name).result.headOption
    }
  }

  lazy val allTransfers = {
    val query = Compiled {
      (tenant: Rep[String], limit: ConstColumn[Long], offset: ConstColumn[Long]) =>
        Transfers
          .filter { row => row.tenant === tenant }
          .sortBy { row => (row.transaction, row.transfer) }
          .drop(offset)
          .take(limit)
    }

    (tenant: String, limit: Long, offset: Long) => persistence.database.run {
      query(tenant, limit, offset).result
    }.map { result =>
      logger.info("allTransfers", result)
      result
    }
  }

  lazy val transfers = {
    val query = Compiled {
      (tenant: Rep[String], transfers: Rep[List[String]]) =>
        Transfers
          .filter { row => row.tenant === tenant }
          .filter { row => row.transfer === transfers.any }
    }

    (tenant: String, transfers: List[String]) => persistence.database.run {
      query(tenant, transfers).result
    }
  }

  lazy val transfer = {
    val query = Compiled {
      (tenant: Rep[String], transaction: Rep[String], transfer: Rep[String]) =>
        Transfers
          .filter { row => row.tenant === tenant }
          .filter { row => row.transaction === transaction }
          .filter { row => row.transfer === transfer }
    }

    (tenant: String, transaction: String, transfer: String) => persistence.database.run {
      query(tenant, transaction, transfer).result.headOption
    }
  }

}
