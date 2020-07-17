package com.openbank.dwh.persistence

import com.typesafe.config.Config
import akka.Done
import com.typesafe.scalalogging.StrictLogging
import scala.concurrent.{ExecutionContext, Future}
import com.openbank.dwh.model._
import scala.math.BigDecimal
import java.time.{ZonedDateTime, ZoneOffset}
import slick.jdbc._
import slick.lifted._
import slick.jdbc.JdbcBackend.Database
import slick.basic.DatabasePublisher
import akka.stream._
import akka.stream.scaladsl._
import java.sql.Timestamp
import akka.http.scaladsl.model.DateTime


object GraphQLPersistence {

  def forConfig(config: Config, ec: ExecutionContext): GraphQLPersistence = {
    new GraphQLPersistence(Postgres.forConfig(config, "graphql.postgresql"))(ec)
  }

}


// FIXME split into interface and impl for better testing
class GraphQLPersistence(val persistence: Postgres)(implicit ec: ExecutionContext) extends StrictLogging {

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
    def * = (tenant, name, currency, format, Rep.None[BigDecimal]) <> ((Account.apply _).tupled, Account.unapply)
    def pk = primaryKey("account_pkey", (tenant, name))
    def tenant_fk = foreignKey("account_tenant_fkey", tenant, Tenants)(_.name)
  }

  val Accounts = TableQuery[AccountTable]

  class AccountBalanceChangeTable(tag: Tag) extends Table[AccountBalance](tag, "account_balance_change") {
    def tenant = column[String]("tenant")
    def name = column[String]("name")
    def valueDate = column[DateTime]("value_date")
    def amount = column[BigDecimal]("amount")

    def * = (tenant, name, valueDate, amount) <> ((AccountBalance.apply _).tupled, AccountBalance.unapply)
  }

  val AccountsBalanceChange = TableQuery[AccountBalanceChangeTable]

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

    (limit: Int, offset: Int) => persistence.database.run {
      query(limit.toLong, offset.toLong).result
    }
  }

  lazy val tenantsByNames = {
    val query = Compiled {
      (names: Rep[List[String]]) =>
        Tenants
          .filter { row => row.name === names.any }
          .sortBy(_.name)
    }

    (names: Iterable[String]) => persistence.database.run {
      query(names.toList).result
    }
  }

  lazy val allAccounts = {
    val query = Compiled {
      (tenant: Rep[String], currency: Rep[Option[String]], format: Rep[Option[String]], limit: ConstColumn[Long], offset: ConstColumn[Long]) =>
        Accounts
          .filter { row => row.tenant === tenant }
          .filter { row => (format.asColumnOf[Option[String]]).isEmpty || row.format === format }
          .filter { row => (currency.asColumnOf[Option[String]]).isEmpty || row.currency >= currency }
          .sortBy(_.name)
          .drop(offset)
          .take(limit)
    }

    (tenant: String, currency: Option[String], format: Option[String], limit: Int, offset: Int) => persistence.database.run {
      query(tenant, currency, format, limit.toLong, offset.toLong).result
    }
  }

  lazy val accountsByNames = {
    val query = Compiled {
      (tenant: Rep[String], names: Rep[List[String]]) =>
        Accounts
          .filter { row => row.tenant === tenant }
          .filter { row => row.name === names.any }
          .sortBy(_.name)
    }

    (tenant: String, names: Iterable[String]) => persistence.database.run {
      query(tenant, names.toList).result
    }
  }

  lazy val allTransfers = {
    val query = Compiled {
      (tenant: Rep[String], currency: Rep[Option[String]], amountGte: Rep[Option[BigDecimal]], amountLte: Rep[Option[BigDecimal]], valueDateGte: Rep[Option[DateTime]], valueDateLte: Rep[Option[DateTime]], limit: ConstColumn[Long], offset: ConstColumn[Long]) =>
        Transfers
          .filter { row => row.tenant === tenant }
          .filter { row => (amountGte.asColumnOf[Option[BigDecimal]]).isEmpty || row.amount <= amountGte }
          .filter { row => (amountLte.asColumnOf[Option[BigDecimal]]).isEmpty || row.amount >= amountLte }
          .filter { row => (valueDateGte.asColumnOf[Option[DateTime]]).isEmpty || row.valueDate <= valueDateGte }
          .filter { row => (valueDateLte.asColumnOf[Option[DateTime]]).isEmpty || row.valueDate >= valueDateLte }
          .filter { row => (currency.asColumnOf[Option[String]]).isEmpty || row.currency >= currency }
          .sortBy { row => (row.transaction, row.transfer) }
          .drop(offset)
          .take(limit)
    }

    (tenant: String, currency: Option[String], amountGte: Option[BigDecimal], amountLte: Option[BigDecimal], valueDateGte: Option[DateTime], valueDateLte: Option[DateTime], limit: Int, offset: Int) => persistence.database.run {
      query(tenant, currency, amountGte, amountLte, valueDateGte, valueDateLte, limit.toLong, offset.toLong).result
    }
  }

  lazy val accountBalance = {
    val query = Compiled {
      (tenant: Rep[String], name: Rep[String]) =>
        AccountsBalanceChange
          .filter { row => row.tenant === tenant }
          .filter { row => row.name === name }
          .map { row => row.amount }
          .sum
    }

    (tenant: String, name: String) => persistence.database.run {
      query(tenant, name).result
    }
  }

}
