package com.openbank.dwh.model

import sangria.execution.deferred.{Fetcher, HasId}
import sangria.schema._
import scala.concurrent.Future
import com.openbank.dwh.persistence._
import akka.stream.scaladsl._
import akka.stream._


// https://github.com/sangria-graphql/sangria-subscriptions-example/tree/master/src/main/scala

object SchemaDefinition {

  import SecondaryPersistence._

  val Account = ObjectType(
    "account",
    "Virtual Account",
    fields[SecondaryPersistence, Account](
      Field("tenant", StringType, resolve = _.value.tenant),
      Field("name", StringType, resolve = _.value.name),
      Field("format", StringType, resolve = _.value.format),
      Field("currency", StringType, resolve = _.value.currency)
    )
  )

  val Tenant = ObjectType(
    "tenant",
    "Domain separation",
    fields[SecondaryPersistence, Tenant](
      Field("name", StringType, resolve = _.value.name)
    )
  )

  val FilterTenant = Argument("tenant", StringType, description = "tenant name")

  val FilterAccountName = Argument("name", StringType, description = "virtual account name")

  val QueryType = ObjectType(
    "Query", fields[SecondaryPersistence, Unit](
      Field(
        "tenant",
        OptionType(Tenant),
        arguments = FilterTenant :: Nil,
        resolve = (ctx) => ctx.ctx.getTenant(ctx.arg(FilterTenant))
      ),
      Field(
        "tenants",
        ListType(Tenant),
        arguments = Nil,
        resolve = (ctx) => ctx.ctx.getTenantsAsFuture()
      ),
      Field(
        "account",
        OptionType(Account),
        arguments = FilterTenant :: FilterAccountName :: Nil,
        resolve = (ctx) => ctx.ctx.getAccount(ctx.arg(FilterTenant), ctx.arg(FilterAccountName))
      ),
      Field(
        "accounts",
        ListType(Account),
        arguments = FilterTenant :: Nil,
        resolve = (ctx) => ctx.ctx.getAccountsAsFuture(ctx.arg(FilterTenant))
      )
    )
  )

  val GraphQLSchema = Schema(QueryType)

}
