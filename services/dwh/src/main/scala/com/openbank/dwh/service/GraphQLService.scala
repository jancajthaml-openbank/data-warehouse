package com.openbank.dwh.service

import sangria.execution.deferred.DeferredResolver
import sangria.execution.Executor
import scala.concurrent.{ExecutionContext, Future}
import spray.json._
import akka.http.scaladsl.model.StatusCodes._
import sangria.ast.Document
import com.openbank.dwh.model._
import com.openbank.dwh.persistence._
import sangria.marshalling.sprayJson._


// https://github.com/sangria-graphql/sangria-subscriptions-example/blob/master/src/main/scala/Server.scala
class GraphQLService(secondaryStorage: SecondaryPersistence)(implicit ec: ExecutionContext) {

  def query(query: Document, variables: Option[JsObject]): Future[JsValue] =
    Executor.execute(
      SchemaDefinition.GraphQLSchema,
      query,
      secondaryStorage,
      variables = variables.getOrElse(JsObject()),
      operationName = None,
      middleware = Nil,
      //deferredResolver = DeferredResolver.fetchers(SchemaDefinition.characters)
    )

}
