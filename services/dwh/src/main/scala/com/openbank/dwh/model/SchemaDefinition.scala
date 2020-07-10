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


// on topic of deferred resolution
// https://medium.com/@toxicafunk/graphql-subqueries-with-sangria-735b13b0cfff
// https://sangria-graphql.org/learn/
object SchemaDefinition {

  implicit val tenantHash = HasId[PersistentTenant, String] { tenant => tenant.name }
  implicit val accountHash = HasId[PersistentAccount, String] { account => s"${account.tenant}/${account.name}" }

  val Resolver = DeferredResolver.empty

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

  val TenantType = ObjectType(
    "tenant",
    "A Tenant",
    fields[Unit, PersistentTenant](
      Field("name", StringType, resolve = _.value.name)
    )
  )

  val FilterTenant = Argument("tenant", StringType)
  val FilterName = Argument("name", StringType)
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
      )
    )
  )

  lazy val GraphQLSchema = Schema(QueryType, None, None)

}
