package com.openbank.dwh.service

import scala.concurrent.{ExecutionContext, Future}
import sangria.execution.{ExceptionHandler, Executor, HandledException}
import sangria.parser.QueryParser
import com.typesafe.scalalogging.StrictLogging
import akka.http.scaladsl.model.DateTime
import sangria.schema._
import com.openbank.dwh.model._
import com.openbank.dwh.persistence._
import sangria.execution.deferred._
import sangria.ast.{BigIntValue, IntValue, StringValue}
import spray.json.{JsObject, JsValue}

sealed trait Types {

  protected implicit val NaturalNumberType: ScalarType[Long] = ScalarType[Long](
    "NaturalNumber",
    coerceUserInput = {
      case s: Int if s >= 0  => Right(s.toLong)
      case s: Long if s >= 0 => Right(s)
      case _                 => Left(NaturalNumberCoerceViolation)
    },
    coerceOutput = (s, _) => s,
    coerceInput = {
      case IntValue(s, _, _) if s >= 0 =>
        Right(s.toLong)
      case BigIntValue(s, _, _) if s >= 0 =>
        Right(s.toLong)
      case _ =>
        Left(NaturalNumberCoerceViolation)
    }
  )

  protected implicit val StatusType: ScalarType[Int] = ScalarType[Int](
    "Status",
    coerceOutput = {
      case (0, _) => "queued"
      case (1, _) => "committed"
      case (2, _) => "rollbacked"
      case _      => ""
    },
    coerceInput = {
      case StringValue("queued", _, _, _, _)     => Right(0)
      case StringValue("committed", _, _, _, _)  => Right(1)
      case StringValue("rollbacked", _, _, _, _) => Right(2)
      case _                                     => Left(StatusCoerceViolation)
    },
    coerceUserInput = {
      case "queued"     => Right(0)
      case "committed"  => Right(1)
      case "rollbacked" => Right(2)
      case _            => Left(StatusCoerceViolation)
    }
  )

  protected implicit val DateTimeType: ScalarType[DateTime] =
    ScalarType[DateTime](
      "DateTime",
      coerceOutput = (s, _) => s.toString,
      coerceInput = {
        case StringValue(s, _, _, _, _) =>
          DateTime.fromIsoDateTimeString(s).toRight(DateTimeCoerceViolation)
        case _ => Left(DateTimeCoerceViolation)
      },
      coerceUserInput = {
        case s: String =>
          DateTime.fromIsoDateTimeString(s).toRight(DateTimeCoerceViolation)
        case _ => Left(DateTimeCoerceViolation)
      }
    )

}

sealed trait Filters {
  self: Types =>

  protected val FilterTenant = Argument("tenant", StringType)
  protected val FilterName = Argument("name", StringType)
  protected val FilterCurrency =
    Argument("currency", OptionInputType(StringType))
  protected val FilterStatus = Argument("status", OptionInputType(StatusType))
  protected val FilterFormat = Argument("format", OptionInputType(StringType))
  protected val FilterTransaction = Argument("transaction", StringType)
  protected val FilterTransfer = Argument("transfer", StringType)
  protected val FilterValueDateLt =
    Argument("valueDate_lt", OptionInputType(DateTimeType))
  protected val FilterValueDateLte =
    Argument("valueDate_lte", OptionInputType(DateTimeType))
  protected val FilterValueDateGt =
    Argument("valueDate_gt", OptionInputType(DateTimeType))
  protected val FilterValueDateGte =
    Argument("valueDate_gte", OptionInputType(DateTimeType))
  protected val FilterAmountLt =
    Argument("amount_lt", OptionInputType(BigDecimalType))
  protected val FilterAmountLte =
    Argument("amount_lte", OptionInputType(BigDecimalType))
  protected val FilterAmountGt =
    Argument("amount_gt", OptionInputType(BigDecimalType))
  protected val FilterAmountGte =
    Argument("amount_gte", OptionInputType(BigDecimalType))
  protected val Limit = Argument("limit", NaturalNumberType)
  protected val Offset = Argument("offset", NaturalNumberType)

}

object SchemaDefinition extends Types with Filters {

  // on topic of deferred resolution
  // https://medium.com/@toxicafunk/graphql-subqueries-with-sangria-735b13b0cfff
  // https://sangria-graphql.org/learn/

  implicit val tenantHash: HasId[Tenant, String] =
    HasId[Tenant, String] { tenant =>
      tenant.name
    }

  implicit val accountHash: HasId[Account, (String, String)] =
    HasId[Account, (String, String)] { account =>
      (account.tenant, account.name)
    }

  private val TenantType: ObjectType[Unit, Tenant] = ObjectType(
    "tenant",
    "A Tenant",
    fields[Unit, Tenant](
      Field("name", StringType, resolve = ctx => ctx.value.name)
    )
  )

  val tenants: Fetcher[GraphQLPersistence, Tenant, Tenant, String] =
    Fetcher((ctx: GraphQLPersistence, names: Seq[String]) => ctx.tenantsByNames(names))

  val accounts: Fetcher[GraphQLPersistence, Account, Account, (String, String)] =
    Fetcher((ctx: GraphQLPersistence, ids: Seq[(String, String)]) => {
      Future.reduceLeft {
        ids
          .groupBy(_._1)
          .map { case (tenant, group) =>
            (tenant, group.map(_._2))
          }
          .map { case (tenant, names) =>
            ctx.accountsByNames(tenant, names)
          }
      }(_ ++ _)(ExecutionContext.global)
    })

  private lazy val AccountType: ObjectType[GraphQLPersistence, Account] =
    ObjectType(
      "account",
      "Virtual Account",
      fields[GraphQLPersistence, Account](
        Field(
          "tenant",
          OptionType(TenantType),
          resolve = ctx => tenants.deferOpt(ctx.value.tenant)
        ),
        Field("name", StringType, resolve = ctx => ctx.value.name),
        Field("format", StringType, resolve = ctx => ctx.value.format),
        Field("currency", StringType, resolve = ctx => ctx.value.currency),
        Field(
          "balance",
          BigDecimalType,
          resolve = ctx => ctx.ctx.accountBalance(ctx.value.tenant, ctx.value.name)
        )
      )
    )

  private lazy val TransferType: ObjectType[GraphQLPersistence, Transfer] =
    ObjectType(
      "transfer",
      "Single transfer from Virtual Account to Virtual Account",
      fields[GraphQLPersistence, Transfer](
        Field(
          "tenant",
          OptionType(TenantType),
          resolve = ctx => tenants.deferOpt(ctx.value.tenant)
        ),
        Field(
          "transaction",
          StringType,
          resolve = ctx => ctx.value.transaction
        ),
        Field("transfer", StringType, resolve = ctx => ctx.value.transfer),
        Field("status", StatusType, resolve = ctx => ctx.value.status),
        Field(
          "credit",
          OptionType(AccountType),
          resolve = ctx => accounts.deferOpt((ctx.value.creditTenant, ctx.value.creditAccount))
        ),
        Field(
          "debit",
          OptionType(AccountType),
          resolve = ctx => accounts.deferOpt((ctx.value.debitTenant, ctx.value.debitAccount))
        ),
        Field("currency", StringType, resolve = ctx => ctx.value.currency),
        Field("amount", BigDecimalType, resolve = ctx => ctx.value.amount),
        Field("valueDate", DateTimeType, resolve = ctx => ctx.value.valueDate)
      )
    )

  private lazy val QueryType: ObjectType[GraphQLPersistence, Unit] = ObjectType(
    "Query",
    "top level query for searching over entities.",
    fields[GraphQLPersistence, Unit](
      Field(
        "tenant",
        OptionType(TenantType),
        arguments = FilterTenant :: Nil,
        resolve = query => tenants.deferOpt(query.arg(FilterTenant))
      ),
      Field(
        "tenants",
        ListType(TenantType),
        arguments = Limit :: Offset :: Nil,
        resolve = query =>
          query.ctx.allTenants(
            query.arg(Limit),
            query.arg(Offset)
          )
      ),
      Field(
        "account",
        OptionType(AccountType),
        arguments = FilterTenant :: FilterName :: Nil,
        resolve = query =>
          accounts.deferOpt(
            (
              query.arg(FilterTenant),
              query.arg(FilterName)
            )
          )
      ),
      Field(
        "accounts",
        ListType(AccountType),
        arguments = FilterTenant ::
          FilterCurrency ::
          FilterFormat ::
          Limit ::
          Offset ::
          Nil,
        resolve = query =>
          query.ctx.accounts(
            query.arg(FilterTenant),
            query.arg(FilterCurrency),
            query.arg(FilterFormat),
            query.arg(Limit),
            query.arg(Offset)
          )
      ),
      Field(
        "transfers",
        ListType(TransferType),
        arguments = FilterTenant ::
          FilterCurrency ::
          FilterStatus ::
          FilterAmountLt ::
          FilterAmountLte ::
          FilterAmountGt ::
          FilterAmountGte ::
          FilterValueDateLt ::
          FilterValueDateLte ::
          FilterValueDateGt ::
          FilterValueDateGte ::
          Limit ::
          Offset ::
          Nil,
        resolve = query =>
          query.ctx.transfers(
            query.arg(FilterTenant),
            query.arg(FilterCurrency),
            query.arg(FilterStatus),
            query.arg(FilterAmountLt),
            query.arg(FilterAmountLte),
            query.arg(FilterAmountGt),
            query.arg(FilterAmountGte),
            query.arg(FilterValueDateLt),
            query.arg(FilterValueDateLte),
            query.arg(FilterValueDateGt),
            query.arg(FilterValueDateGte),
            query.arg(Limit),
            query.arg(Offset)
          )
      )
    )
  )

  lazy val GraphResolver: DeferredResolver[GraphQLPersistence] =
    DeferredResolver.fetchers(accounts, tenants)

  lazy val GraphSchema: Schema[GraphQLPersistence, Unit] =
    Schema(QueryType, None, None)

}

class GraphQLService(graphStorage: GraphQLPersistence) extends StrictLogging {

  import sangria.marshalling.sprayJson._

  def execute(
      query: String,
      operation: Option[String],
      variables: JsObject = JsObject.empty
  )(implicit ec: ExecutionContext): Future[JsValue] = {
    Future
      .fromTry(QueryParser.parse(query))
      .flatMap { queryAst =>
        executor
          .execute(queryAst, graphStorage, (), operation, variables)
      }
  }

  private val executor = Executor(
    schema = SchemaDefinition.GraphSchema,
    exceptionHandler = exceptionHandler,
    deferredResolver = SchemaDefinition.GraphResolver
  )(graphStorage.persistence.database.executor.executionContext)

  private lazy val exceptionHandler = ExceptionHandler { case (m, e) =>
    logger.error(s"exception occurred when executing query $m", e)
    HandledException("Internal server error")
  }

}
