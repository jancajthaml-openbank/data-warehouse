package com.openbank.dwh.service

import scala.concurrent.{ExecutionContext, Future}
import sangria.execution.{ExceptionHandler, Executor, HandledException}
import sangria.parser.QueryParser
import com.typesafe.scalalogging.StrictLogging
import akka.http.scaladsl.model.DateTime
import sangria.schema._
import sangria.macros.derive._
import com.openbank.dwh.model._
import com.openbank.dwh.persistence._
import sangria.execution.deferred._
import sangria.ast.StringValue

class GraphQLService(graphStorage: GraphQLPersistence)(implicit
    ec: ExecutionContext
) extends StrictLogging {

  import sangria.marshalling.sprayJson._
  import spray.json._

  def execute(
      query: String,
      operation: Option[String],
      variables: JsObject = JsObject.empty
  ): Future[JsValue] = {
    Future
      .fromTry {
        QueryParser.parse(query)
      }
      .flatMap { queryAst =>
        executor
          .execute(queryAst, graphStorage, (), operation, variables)
      }
  }

  // on topic of deferred resolution
  // https://medium.com/@toxicafunk/graphql-subqueries-with-sangria-735b13b0cfff
  // https://sangria-graphql.org/learn/

  implicit val DateTimeType = ScalarType[DateTime](
    "DateTime",
    coerceOutput = (dt, _) => dt.toString,
    coerceInput = {
      case StringValue(dt, _, _, _, _) =>
        DateTime.fromIsoDateTimeString(dt).toRight(DateTimeCoerceViolation)
      case _ => Left(DateTimeCoerceViolation)
    },
    coerceUserInput = {
      case s: String =>
        DateTime.fromIsoDateTimeString(s).toRight(DateTimeCoerceViolation)
      case _ => Left(DateTimeCoerceViolation)
    }
  )

  implicit val tenantHash = HasId[Tenant, String] { tenant => tenant.name }
  implicit val accountHash = HasId[Account, Tuple2[String, String]] { account =>
    (account.tenant, account.name)
  }

  val tenants = Fetcher((ctx: GraphQLPersistence, names: Seq[String]) =>
    ctx.tenantsByNames(names)
  )

  val accounts =
    Fetcher((ctx: GraphQLPersistence, ids: Seq[Tuple2[String, String]]) => {
      Future.reduceLeft {
        ids
          .groupBy(_._1)
          .map {
            case (tenant, group) => (tenant, group.map(_._2))
          }
          .map {
            case (tenant, names) => ctx.accountsByNames(tenant, names)
          }
      }(_ ++ _)
    })

  lazy val GraphResolver = DeferredResolver.fetchers(accounts, tenants)

  lazy val TenantType = ObjectType(
    "tenant",
    "A Tenant",
    fields[Unit, Tenant](
      Field("name", StringType, resolve = (ctx) => ctx.value.name)
    )
  )

  lazy val AccountType = ObjectType(
    "account",
    "Virtual Account",
    fields[GraphQLPersistence, Account](
      Field(
        "tenant",
        OptionType(TenantType),
        resolve = (ctx) => tenants.deferOpt(ctx.value.tenant)
      ),
      Field("name", StringType, resolve = (ctx) => ctx.value.name),
      Field("format", StringType, resolve = (ctx) => ctx.value.format),
      Field("currency", StringType, resolve = (ctx) => ctx.value.currency),
      Field(
        "balance",
        BigDecimalType,
        resolve = (ctx) =>
          ctx.ctx
            .accountBalance(ctx.value.tenant, ctx.value.name)
            .map(_.getOrElse(0: BigDecimal))
      )
    )
  )

  lazy val TransferType = ObjectType(
    "transfer",
    "Single transfer from Virtual Account to Virtual Account",
    fields[Unit, Transfer](
      Field(
        "tenant",
        OptionType(TenantType),
        resolve = (ctx) => tenants.deferOpt(ctx.value.tenant)
      ),
      Field(
        "transaction",
        StringType,
        resolve = (ctx) => ctx.value.transaction
      ),
      Field("transfer", StringType, resolve = (ctx) => ctx.value.transfer),
      Field("status", IntType, resolve = (ctx) => ctx.value.status),
      Field(
        "credit",
        OptionType(AccountType),
        resolve = (ctx) =>
          accounts.deferOpt((ctx.value.creditTenant, ctx.value.creditAccount))
      ),
      Field(
        "debit",
        OptionType(AccountType),
        resolve = (ctx) =>
          accounts.deferOpt((ctx.value.debitTenant, ctx.value.debitAccount))
      ),
      Field("currency", StringType, resolve = (ctx) => ctx.value.currency),
      Field("amount", BigDecimalType, resolve = (ctx) => ctx.value.amount),
      Field("valueDate", DateTimeType, resolve = (ctx) => ctx.value.valueDate)
    )
  )

  val FilterTenant = Argument("tenant", StringType)

  val FilterName = Argument("name", StringType)

  val FilterCurrency = Argument("currency", OptionInputType(StringType))

  val FilterStatus = Argument("status", OptionInputType(IntType))

  val FilterFofmat = Argument("format", OptionInputType(StringType))

  val FilterTransaction = Argument("transaction", StringType)

  val FilterTransfer = Argument("transfer", StringType)

  val FilterValueDateFrom =
    Argument("valueDateLte", OptionInputType(DateTimeType))

  val FilterValueDateTo =
    Argument("valueDateGte", OptionInputType(DateTimeType))

  val FilterAmountFrom = Argument("amountLte", OptionInputType(BigDecimalType))

  val FilterAmountTo = Argument("amountGte", OptionInputType(BigDecimalType))

  // FIXME should be positive number -> NaturalLongType scalar
  val Limit = Argument("limit", IntType)

  // FIXME should be positive number -> NaturalLongType scalar
  val Offset = Argument("offset", IntType)

  lazy val QueryType = ObjectType(
    "Query",
    "top level query for searching over entities.",
    fields[GraphQLPersistence, Unit](
      Field(
        "tenant",
        OptionType(TenantType),
        arguments = FilterTenant ::
          Nil,
        resolve = (query) =>
          tenants.deferOpt(
            query.arg(FilterTenant)
          )
      ),
      Field(
        "tenants",
        ListType(TenantType),
        arguments = Limit ::
          Offset ::
          Nil,
        resolve = (query) =>
          query.ctx.allTenants(
            query.arg(Limit),
            query.arg(Offset)
          )
      ),
      Field(
        "account",
        OptionType(AccountType),
        arguments = FilterTenant ::
          FilterName ::
          Nil,
        resolve = (query) =>
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
          FilterFofmat ::
          Limit ::
          Offset ::
          Nil,
        resolve = (query) =>
          query.ctx.allAccounts(
            query.arg(FilterTenant),
            query.arg(FilterCurrency),
            query.arg(FilterFofmat),
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
          FilterAmountFrom ::
          FilterAmountTo ::
          FilterValueDateFrom ::
          FilterValueDateTo ::
          Limit ::
          Offset ::
          Nil,
        resolve = (query) =>
          query.ctx.allTransfers(
            query.arg(FilterTenant),
            query.arg(FilterCurrency),
            query.arg(FilterStatus),
            query.arg(FilterAmountFrom),
            query.arg(FilterAmountTo),
            query.arg(FilterValueDateFrom),
            query.arg(FilterValueDateTo),
            query.arg(Limit),
            query.arg(Offset)
          )
      )
    )
  )

  lazy val GraphSchema = Schema(QueryType, None, None)

  private val executor = Executor(
    schema = GraphSchema,
    exceptionHandler = exceptionHandler,
    deferredResolver = GraphResolver
  )

  private lazy val exceptionHandler = ExceptionHandler {
    case (m, e) =>
      logger.error(s"exception occured when executing query ${m} ${e}")
      HandledException("Internal server error")
  }

}
