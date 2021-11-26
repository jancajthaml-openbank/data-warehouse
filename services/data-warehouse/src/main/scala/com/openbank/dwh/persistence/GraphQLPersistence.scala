package com.openbank.dwh.persistence

import com.typesafe.config.Config
import com.typesafe.scalalogging.StrictLogging
import com.openbank.dwh.model._

import scala.math.BigDecimal
import java.sql.Timestamp
import akka.http.scaladsl.model.DateTime
import slick.ast.BaseTypedType
import slick.jdbc.{JdbcType, ResultSetConcurrency, ResultSetType}
import slick.lifted.{CompiledFunction, ProvenShape}

import scala.concurrent.Future

object GraphQLPersistence {

  def forConfig(config: Config): GraphQLPersistence = {
    new GraphQLPersistence(Postgres.forConfig(config, "graphql.postgresql"))
  }

}

// FIXME split into interface and impl for better testing
class GraphQLPersistence(val persistence: Postgres) extends StrictLogging {

  import persistence.profile.api._

  implicit val dateTimeColumnType: JdbcType[DateTime] with BaseTypedType[DateTime] =
    MappedColumnType.base[DateTime, Timestamp](
      dt => new Timestamp(dt.clicks),
      ts => DateTime(ts.getTime)
    )

  class TenantTable(tag: Tag) extends Table[Tenant](tag, "tenant") {
    def name: Rep[String] = column[String]("name")

    def * : ProvenShape[Tenant] = {
      name <> (Tenant.apply, Tenant.unapply)
    }

    def pk = primaryKey("tenant_pkey", name)
  }

  class AccountTable(tag: Tag) extends Table[Account](tag, "account") {
    def tenant: Rep[String] = column[String]("tenant")

    def name: Rep[String] = column[String]("name")

    def currency: Rep[String] = column[String]("currency")

    def format: Rep[String] = column[String]("format")

    def * : ProvenShape[Account] =
      (
        tenant,
        name,
        currency,
        format,
        Rep.None[BigDecimal]
      ) <> ((Account.apply _).tupled, Account.unapply)

    def pk = primaryKey("account_pkey", (tenant, name))

    def tenant_fk = foreignKey("account_tenant_fkey", tenant, Tenants)(_.name)
  }

  class AccountBalanceChangeTable(tag: Tag)
      extends Table[AccountBalance](tag, "account_balance_change") {
    def tenant: Rep[String] = column[String]("tenant")

    def name: Rep[String] = column[String]("name")

    def valueDate: Rep[DateTime] = column[DateTime]("value_date")

    def amount: Rep[BigDecimal] = column[BigDecimal]("amount")

    def * : ProvenShape[AccountBalance] =
      (
        tenant,
        name,
        valueDate,
        amount
      ) <> ((AccountBalance.apply _).tupled, AccountBalance.unapply)
  }

  class TransferTable(tag: Tag) extends Table[Transfer](tag, "transfer") {
    def tenant: Rep[String] = column[String]("tenant")

    def transaction: Rep[String] = column[String]("transaction")

    def transfer: Rep[String] = column[String]("transfer")

    def status: Rep[Int] = column[Int]("status")

    def creditTenant: Rep[String] = column[String]("credit_tenant")

    def creditAccount: Rep[String] = column[String]("credit_name")

    def debitTenant: Rep[String] = column[String]("debit_tenant")

    def debitAccount: Rep[String] = column[String]("debit_name")

    def currency: Rep[String] = column[String]("currency")

    def amount: Rep[BigDecimal] = column[BigDecimal]("amount")

    def valueDate: Rep[DateTime] = column[DateTime]("value_date")

    def * : ProvenShape[Transfer] =
      (
        tenant,
        transaction,
        transfer,
        status,
        creditTenant,
        creditAccount,
        debitTenant,
        debitAccount,
        amount,
        currency,
        valueDate
      ) <> ((Transfer.apply _).tupled, Transfer.unapply)

    def pk = primaryKey("transfer_pkey", (tenant, transaction, transfer))

    def tenant_fk = foreignKey("transfer_tenant_fkey", tenant, Tenants)(_.name)

    def credit_fk =
      foreignKey(
        "transfer_credit_tenant_credit_name_fkey",
        (creditTenant, creditAccount),
        Accounts
      ) { account => (account.tenant, account.name) }

    def debit_fk =
      foreignKey(
        "transfer_debit_tenant_debit_name_fkey",
        (debitTenant, debitAccount),
        Accounts
      ) { account => (account.tenant, account.name) }
  }

  private val Tenants = TableQuery[TenantTable]
  private val Accounts = TableQuery[AccountTable]
  private val AccountsBalanceChange = TableQuery[AccountBalanceChangeTable]
  private val Transfers = TableQuery[TransferTable]

  val allTenants: (Long, Long) => Future[Seq[Tenant]] = {
    val query = Compiled { (limit: ConstColumn[Long], offset: ConstColumn[Long]) =>
      Tenants
        .sortBy(_.name)
        .drop(offset)
        .take(limit)
    }

    (limit: Long, offset: Long) =>
      persistence.database.run {
        query(limit, offset).result.withStatementParameters(
          rsType = ResultSetType.ForwardOnly,
          rsConcurrency = ResultSetConcurrency.ReadOnly,
          fetchSize = 200
        )
      }
  }

  val tenantsByNames: Iterable[String] => Future[Seq[Tenant]] = {
    val query = Compiled { (names: Rep[List[String]]) =>
      Tenants
        .filter { row => row.name === names.any }
        .sortBy(_.name)
    }

    (names: Iterable[String]) =>
      persistence.database.run {
        query(names.toList).result.withStatementParameters(
          rsType = ResultSetType.ForwardOnly,
          rsConcurrency = ResultSetConcurrency.ReadOnly,
          fetchSize = 10
        )
      }
  }

  val accounts: (String, Option[String], Option[String], Long, Long) => Future[
    Seq[Account]
  ] = {
    val query = Compiled {
      (
          tenant: Rep[String],
          currency: Rep[Option[String]],
          format: Rep[Option[String]],
          limit: ConstColumn[Long],
          offset: ConstColumn[Long]
      ) =>
        Accounts
          .filter { row => row.tenant === tenant }
          .filter { row =>
            format.asColumnOf[Option[String]].isEmpty || row.format === format
          }
          .filter { row =>
            currency
              .asColumnOf[Option[String]]
              .isEmpty || row.currency >= currency
          }
          .sortBy(_.name)
          .drop(offset)
          .take(limit)
    }

    (
        tenant: String,
        currency: Option[String],
        format: Option[String],
        limit: Long,
        offset: Long
    ) =>
      persistence.database.run {
        query(tenant, currency, format, limit, offset).result.withStatementParameters(
          rsType = ResultSetType.ForwardOnly,
          rsConcurrency = ResultSetConcurrency.ReadOnly,
          fetchSize = 200
        )
      }
  }

  val accountsByNames: (String, Iterable[String]) => Future[Seq[Account]] = {
    val query = Compiled { (tenant: Rep[String], names: Rep[List[String]]) =>
      Accounts
        .filter { row => row.tenant === tenant }
        .filter { row => row.name === names.any }
        .sortBy(_.name)
    }

    (tenant: String, names: Iterable[String]) =>
      persistence.database.run {
        query(tenant, names.toList).result.withStatementParameters(
          rsType = ResultSetType.ForwardOnly,
          rsConcurrency = ResultSetConcurrency.ReadOnly,
          fetchSize = 10
        )
      }
  }

  val transfers: (
      String,
      Option[String],
      Option[Int],
      Option[BigDecimal],
      Option[BigDecimal],
      Option[BigDecimal],
      Option[BigDecimal],
      Option[DateTime],
      Option[DateTime],
      Option[DateTime],
      Option[DateTime],
      Long,
      Long
  ) => Future[Seq[Transfer]] = {
    val query = Compiled {
      (
          tenant: Rep[String],
          currency: Rep[Option[String]],
          status: Rep[Option[Int]],
          amountLt: Rep[Option[BigDecimal]],
          amountLte: Rep[Option[BigDecimal]],
          amountGt: Rep[Option[BigDecimal]],
          amountGte: Rep[Option[BigDecimal]],
          valueDateLt: Rep[Option[DateTime]],
          valueDateLte: Rep[Option[DateTime]],
          valueDateGt: Rep[Option[DateTime]],
          valueDateGte: Rep[Option[DateTime]],
          limit: ConstColumn[Long],
          offset: ConstColumn[Long]
      ) =>
        Transfers
          .filter { row => row.tenant === tenant }
          .filter { row =>
            amountGte
              .asColumnOf[Option[BigDecimal]]
              .isEmpty || row.amount <= amountGte
          }
          .filter { row =>
            amountGt
              .asColumnOf[Option[BigDecimal]]
              .isEmpty || row.amount < amountGt
          }
          .filter { row =>
            amountLte
              .asColumnOf[Option[BigDecimal]]
              .isEmpty || row.amount >= amountLte
          }
          .filter { row =>
            amountLt
              .asColumnOf[Option[BigDecimal]]
              .isEmpty || row.amount > amountLt
          }
          .filter { row =>
            valueDateGte
              .asColumnOf[Option[DateTime]]
              .isEmpty || row.valueDate <= valueDateGte
          }
          .filter { row =>
            valueDateGt
              .asColumnOf[Option[DateTime]]
              .isEmpty || row.valueDate <= valueDateGt
          }
          .filter { row =>
            valueDateLte
              .asColumnOf[Option[DateTime]]
              .isEmpty || row.valueDate >= valueDateLte
          }
          .filter { row =>
            valueDateLt
              .asColumnOf[Option[DateTime]]
              .isEmpty || row.valueDate >= valueDateLt
          }
          .filter { row =>
            currency
              .asColumnOf[Option[String]]
              .isEmpty || row.currency === currency
          }
          .filter { row =>
            status
              .asColumnOf[Option[Int]]
              .isEmpty || row.status === status
          }
          .sortBy { row => (row.transaction, row.transfer) }
          .drop(offset)
          .take(limit)
    }

    (
        tenant: String,
        currency: Option[String],
        status: Option[Int],
        amountLt: Option[BigDecimal],
        amountLte: Option[BigDecimal],
        amountGt: Option[BigDecimal],
        amountGte: Option[BigDecimal],
        valueDateLt: Option[DateTime],
        valueDateLte: Option[DateTime],
        valueDateGt: Option[DateTime],
        valueDateGte: Option[DateTime],
        limit: Long,
        offset: Long
    ) =>
      persistence.database.run {
        query(
          tenant,
          currency,
          status,
          amountLt,
          amountLte,
          amountGt,
          amountGte,
          valueDateLt,
          valueDateLte,
          valueDateGt,
          valueDateGte,
          limit,
          offset
        ).result.withStatementParameters(
          rsType = ResultSetType.ForwardOnly,
          rsConcurrency = ResultSetConcurrency.ReadOnly,
          fetchSize = 10
        )
      }
  }

  val accountBalance: (String, String) => Future[BigDecimal] = {
    val query: CompiledFunction[
      (
          persistence.profile.api.Rep[String],
          persistence.profile.api.Rep[String]
      ) => Rep[Option[BigDecimal]],
      (
          persistence.profile.api.Rep[String],
          persistence.profile.api.Rep[String]
      ),
      (String, String),
      Rep[Option[BigDecimal]],
      Option[BigDecimal]
    ] = Compiled { (tenant: Rep[String], name: Rep[String]) =>
      AccountsBalanceChange
        .filter { row => row.tenant === tenant }
        .filter { row => row.name === name }
        .map { row => row.amount }
        .sum
    }

    (tenant: String, name: String) =>
      persistence.database
        .run {
          query(tenant, name).result.withStatementParameters(
            rsType = ResultSetType.ForwardOnly,
            rsConcurrency = ResultSetConcurrency.ReadOnly,
            fetchSize = 10
          )
        }
        .map(_.getOrElse(0: BigDecimal))(
          persistence.database.executor.executionContext
        )
  }

}
