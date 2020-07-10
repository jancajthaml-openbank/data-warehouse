package com.openbank.dwh.model

import sangria.execution.deferred.{Fetcher, HasId}
import sangria.schema._
import sangria.macros.derive._
import scala.concurrent.Future
import com.openbank.dwh.persistence._
import akka.stream.scaladsl._
import akka.stream._
import sangria.execution.deferred.{Fetcher, HasId}
import sangria.execution.deferred.DeferredResolver
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import sangria.validation.Violation
import sangria.ast.StringValue

import scala.util.{Try, Success, Failure}


// on topic of deferred resolution
// https://medium.com/@toxicafunk/graphql-subqueries-with-sangria-735b13b0cfff
// https://sangria-graphql.org/learn/
object SchemaDefinition {

  implicit val tenantHash = HasId[PersistentTenant, String] { tenant => tenant.name }
  implicit val accountHash = HasId[PersistentAccount, String] { account => s"${account.tenant}/${account.name}" }
  implicit val transferHash = HasId[PersistentTransfer, String] { transfer => s"${transfer.tenant}/${transfer.transaction}/${transfer.transfer}" }

  val Resolver = DeferredResolver.empty

  case object DateTimeCoerceViolation extends Violation {
    override def errorMessage: String = "Error during parsing DateTime"
  }

  val DateTimeType = ScalarType[ZonedDateTime](
    "DateTime",
    coerceOutput = (dt, _) => dt.format(DateTimeFormatter.ISO_INSTANT),
    coerceInput = {
      case StringValue(dt, _, _, _, _) => Try(ZonedDateTime.parse(dt)) match {
        case Success(v) => Right(v)
        case Failure(_) => Left(DateTimeCoerceViolation)
      }
      case _ => Left(DateTimeCoerceViolation)
    },
    coerceUserInput = {
      case s: String => Try(ZonedDateTime.parse(s)) match {
        case Success(v) => Right(v)
        case Failure(_) => Left(DateTimeCoerceViolation)
      }
      case _ => Left(DateTimeCoerceViolation)
    }
  )

  val AccountType = ObjectType(
    "account",
    "Virtual Account",
    fields[Unit, PersistentAccount](
      Field("tenant", StringType, resolve = _.value.tenant),
      Field("name", StringType, resolve = _.value.name),
      Field("format", StringType, resolve = _.value.format),
      Field("currency", StringType, resolve = _.value.currency)
    )
  )

  val TransferType = ObjectType(
    "transfer",
    "Single transfer from Virtual Account to Virtual Account",
    fields[Unit, PersistentTransfer](
      Field("tenant", StringType, resolve = _.value.tenant),
      Field("transaction", StringType, resolve = _.value.transaction),
      Field("transfer", StringType, resolve = _.value.transfer),
      Field("creditAccount", StringType, resolve = _.value.creditTenant),
      Field("creditName", StringType, resolve = _.value.creditAccount),
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
    fields[Unit, PersistentTenant](
      Field("name", StringType, resolve = _.value.name)
    )
  )

  val FilterTenant = Argument("tenant", StringType)
  val FilterName = Argument("name", StringType)
  val FilterTransaction = Argument("transaction", StringType)
  val FilterTransfer = Argument("transfer", StringType)
  val Limit = Argument("limit", OptionInputType(IntType))
  val Offset = Argument("offset", OptionInputType(IntType))

  val QueryType = ObjectType(
    "query",
    "top level query for listing `account` and `tenant` entities.",
    fields[SecondaryPersistence, Unit](
      Field(
        "tenant",
        OptionType(TenantType),
        arguments = FilterTenant :: Nil,
        resolve = (query) => query.ctx.getTenant(query.arg(FilterTenant))
      ),
      Field(
        "tenants",
        ListType(TenantType),
        arguments = Nil,
        resolve = (query) => query.ctx.getTenantsAsFuture()
      ),
      Field(
        "account",
        OptionType(AccountType),
        arguments = FilterTenant :: FilterName :: Nil,
        resolve = (query) => query.ctx.getAccount(query.arg(FilterTenant), query.arg(FilterName))
      ),
      Field(
        "accounts",
        ListType(AccountType),
        arguments = FilterTenant :: Nil,
        resolve = (query) => query.ctx.getAccountsAsFuture(query.arg(FilterTenant))
      ),
      Field(
        "transfer",
        OptionType(TransferType),
        arguments = FilterTenant :: FilterTransaction :: FilterTransfer :: Nil,
        resolve = (query) => query.ctx.getTransfer(query.arg(FilterTenant), query.arg(FilterTransaction), query.arg(FilterTransfer))
      ),
      Field(
        "transfers",
        ListType(TransferType),
        arguments = FilterTenant :: Nil,
        resolve = (query) => query.ctx.getTransfersAsFuture(query.arg(FilterTenant))
      )
    )
  )

  lazy val GraphQLSchema = Schema(QueryType, None, None)

}
