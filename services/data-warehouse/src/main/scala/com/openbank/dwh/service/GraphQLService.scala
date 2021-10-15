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
import sangria.ast.StringValue

class GraphQLService(graphStorage: GraphQLPersistence) extends StrictLogging {

  import sangria.marshalling.sprayJson._
  import spray.json._

  def execute(
      query: String,
      operation: Option[String],
      variables: JsObject = JsObject.empty
  )(implicit ec: ExecutionContext): Future[JsValue] = {
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

  implicit val DateTimeType: ScalarType[DateTime] = ScalarType[DateTime](
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

  implicit val tenantHash: HasId[Tenant, String] = HasId[Tenant, String] {
    tenant => tenant.name
  }
  implicit val accountHash: HasId[Account, (String, String)] =
    HasId[Account, (String, String)] { account =>
      (account.tenant, account.name)
    }

  val tenants: Fetcher[GraphQLPersistence, Tenant, Tenant, String] =
    Fetcher((ctx: GraphQLPersistence, names: Seq[String]) =>
      ctx.tenantsByNames(names)
    )

  val accounts
      : Fetcher[GraphQLPersistence, Account, Account, (String, String)] =
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

  lazy val GraphResolver: DeferredResolver[GraphQLPersistence] =
    DeferredResolver.fetchers(accounts, tenants)

  lazy val TenantType: ObjectType[Unit, Tenant] = ObjectType(
    "tenant",
    "A Tenant",
    fields[Unit, Tenant](
      Field("name", StringType, resolve = ctx => ctx.value.name)
    )
  )

  lazy val AccountType: ObjectType[GraphQLPersistence, Account] = ObjectType(
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
        resolve = ctx =>
          ctx.ctx
            .accountBalance(ctx.value.tenant, ctx.value.name)
            .map(_.getOrElse(0: BigDecimal))(ExecutionContext.global)
      )
    )
  )

  lazy val TransferType: ObjectType[Unit, Transfer] = ObjectType(
    "transfer",
    "Single transfer from Virtual Account to Virtual Account",
    fields[Unit, Transfer](
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
      Field(
        "status",
        StringType,
        resolve = ctx =>
          ctx.value.status match {
            case 0 => "queued"
            case 1 => "committed"
            case 2 => "rollbacked"
          }
      ),
      Field(
        "credit",
        OptionType(AccountType),
        resolve = ctx =>
          accounts.deferOpt((ctx.value.creditTenant, ctx.value.creditAccount))
      ),
      Field(
        "debit",
        OptionType(AccountType),
        resolve = ctx =>
          accounts.deferOpt((ctx.value.debitTenant, ctx.value.debitAccount))
      ),
      Field("currency", StringType, resolve = ctx => ctx.value.currency),
      Field("amount", BigDecimalType, resolve = ctx => ctx.value.amount),
      Field("valueDate", DateTimeType, resolve = ctx => ctx.value.valueDate)
    )
  )

  val FilterTenant: Argument[String] = Argument("tenant", StringType)

  val FilterName: Argument[String] = Argument("name", StringType)

  val FilterCurrency: Argument[Option[String]] =
    Argument("currency", OptionInputType(StringType))

  // FIXME String
  val FilterStatus: Argument[Option[Int]] =
    Argument("status", OptionInputType(IntType))

  val FilterFormat: Argument[Option[String]] =
    Argument("format", OptionInputType(StringType))

  val FilterTransaction: Argument[String] = Argument("transaction", StringType)

  val FilterTransfer: Argument[String] = Argument("transfer", StringType)

  val FilterValueDateFrom: Argument[Option[DateTime]] =
    Argument("valueDateLte", OptionInputType(DateTimeType))

  val FilterValueDateTo: Argument[Option[DateTime]] =
    Argument("valueDateGte", OptionInputType(DateTimeType))

  val FilterAmountFrom: Argument[Option[BigDecimal]] =
    Argument("amountLte", OptionInputType(BigDecimalType))

  val FilterAmountTo: Argument[Option[BigDecimal]] =
    Argument("amountGte", OptionInputType(BigDecimalType))

  // FIXME should be positive number -> NaturalLongType scalar
  val Limit: Argument[Int] = Argument("limit", IntType)

  // FIXME should be positive number -> NaturalLongType scalar
  val Offset: Argument[Int] = Argument("offset", IntType)

  lazy val QueryType: ObjectType[GraphQLPersistence, Unit] = ObjectType(
    "Query",
    "top level query for searching over entities.",
    fields[GraphQLPersistence, Unit](
      Field(
        "tenant",
        OptionType(TenantType),
        arguments = FilterTenant ::
          Nil,
        resolve = query =>
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
        resolve = query =>
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
          query.ctx.allAccounts(
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
          FilterAmountFrom ::
          FilterAmountTo ::
          FilterValueDateFrom ::
          FilterValueDateTo ::
          Limit ::
          Offset ::
          Nil,
        resolve = query =>
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

  lazy val GraphSchema: Schema[GraphQLPersistence, Unit] =
    Schema(QueryType, None, None)

  private val executor = Executor(
    schema = GraphSchema,
    exceptionHandler = exceptionHandler,
    deferredResolver = GraphResolver
  )(graphStorage.persistence.database.executor.executionContext)

  private lazy val exceptionHandler = ExceptionHandler { case (m, e) =>
    logger.error(s"exception occurred when executing query $m $e")
    HandledException("Internal server error")
  }

}
