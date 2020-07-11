package com.openbank.dwh.model

import scala.math.BigDecimal
import akka.http.scaladsl.model.DateTime
import sangria.execution.deferred.{Fetcher, HasId}
import sangria.schema._
import sangria.macros.derive._
import com.openbank.dwh.persistence.GraphQLPersistence
import sangria.execution.deferred.{Fetcher, HasId}
import sangria.execution.deferred.DeferredResolver
import sangria.validation.Violation
import sangria.ast.StringValue


case class Tenant(
  name: String
)

case class Account(
  tenant: String,
  name: String,
  currency: String,
  format: String
)

case class Transfer(
  tenant: String,
  transaction: String,
  transfer: String,
  creditTenant: String,
  creditAccount: String,
  debitTenant: String,
  debitAccount: String,
  amount: BigDecimal,
  currency: String,
  valueDate: DateTime
)


// on topic of deferred resolution
// https://medium.com/@toxicafunk/graphql-subqueries-with-sangria-735b13b0cfff
// https://sangria-graphql.org/learn/
object GraphQL {

  implicit val tenantHash = HasId[Tenant, String] { tenant => tenant.name }
  implicit val accountHash = HasId[Account, String] { account => s"${account.tenant}/${account.name}" }
  implicit val transferHash = HasId[Transfer, String] { transfer => s"${transfer.tenant}/${transfer.transaction}/${transfer.transfer}" }

  val Resolver = DeferredResolver.empty

  case object DateTimeCoerceViolation extends Violation {
    override def errorMessage: String = "Error during parsing DateTime"
  }

  implicit val DateTimeType = ScalarType[DateTime](
    "DateTime",
    coerceOutput = (dt, _) => dt.toString,
    coerceInput = {
      case StringValue(dt, _, _, _, _) => DateTime.fromIsoDateTimeString(dt).toRight(DateTimeCoerceViolation)
      case _ => Left(DateTimeCoerceViolation)
    },
    coerceUserInput = {
      case s: String => DateTime.fromIsoDateTimeString(s).toRight(DateTimeCoerceViolation)
      case _ => Left(DateTimeCoerceViolation)
    }
  )

  val AccountType = ObjectType(
    "account",
    "Virtual Account",
    fields[Unit, Account](
      Field("tenant", StringType, resolve = _.value.tenant),
      Field("name", StringType, resolve = _.value.name),
      Field("format", StringType, resolve = _.value.format),
      Field("currency", StringType, resolve = _.value.currency)
    )
  )

  val TransferType = ObjectType(
    "transfer",
    "Single transfer from Virtual Account to Virtual Account",
    fields[Unit, Transfer](
      Field("tenant", StringType, resolve = _.value.tenant),
      Field("transaction", StringType, resolve = _.value.transaction),
      Field("transfer", StringType, resolve = _.value.transfer),
      Field("creditTenant", StringType, resolve = _.value.creditTenant),
      Field("creditAccount", StringType, resolve = _.value.creditAccount),
      Field("debitTenant", StringType, resolve = _.value.debitTenant),
      Field("debitAccount", StringType, resolve = _.value.debitAccount),
      Field("currency", StringType, resolve = _.value.currency),
      Field("amount", BigDecimalType, resolve = _.value.amount),
      Field("valueDate", DateTimeType, resolve = _.value.valueDate)
    )
  )

  val TenantType = ObjectType(
    "tenant",
    "A Tenant",
    fields[Unit, Tenant](
      Field("name", StringType, resolve = _.value.name)
    )
  )

  val FilterTenant = Argument("tenant", StringType)
  val FilterName = Argument("name", StringType)
  val FilterTransaction = Argument("transaction", StringType)
  val FilterTransfer = Argument("transfer", StringType)

  // FIXME should be positive number -> NaturalLongType scalar
  val Limit = Argument("limit", LongType)
  // FIXME should be positive number -> NaturalLongType scalar
  val Offset = Argument("offset", LongType)

  val QueryType = ObjectType(
    "query",
    "top level query for listing `account` and `tenant` entities.",
    fields[GraphQLPersistence, Unit](
      Field(
        "tenant",
        OptionType(TenantType),
        arguments = FilterTenant :: Nil,
        resolve = (query) => query.ctx.tenant(query.arg(FilterTenant))
      ),
      Field(
        "tenants",
        ListType(TenantType),
        arguments = Limit :: Offset :: Nil,
        resolve = (query) => query.ctx.allTenants(query.arg(Limit), query.arg(Offset))
      ),
      Field(
        "account",
        OptionType(AccountType),
        arguments = FilterTenant :: FilterName :: Nil,
        resolve = (query) => query.ctx.account(query.arg(FilterTenant), query.arg(FilterName))
      ),
      Field(
        "accounts",
        ListType(AccountType),
        arguments = FilterTenant :: Limit :: Offset :: Nil,
        resolve = (query) => query.ctx.allAccounts(query.arg(FilterTenant), query.arg(Limit), query.arg(Offset))
      ),
      Field(
        "transfer",
        OptionType(TransferType),
        arguments = FilterTenant :: FilterTransaction :: FilterTransfer :: Nil,
        resolve = (query) => query.ctx.transfer(query.arg(FilterTenant), query.arg(FilterTransaction), query.arg(FilterTransfer))
      ),
      Field(
        "transfers",
        ListType(TransferType),
        arguments = FilterTenant :: Limit :: Offset :: Nil,
        resolve = (query) => query.ctx.allTransfers(query.arg(FilterTenant), query.arg(Limit), query.arg(Offset))
      )
    )
  )

  lazy val GraphSchema = Schema(QueryType, None, None)

}
