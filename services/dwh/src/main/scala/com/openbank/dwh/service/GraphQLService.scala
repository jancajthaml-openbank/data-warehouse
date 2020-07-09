package com.openbank.dwh.service

import scala.concurrent.{ExecutionContext, Future}
import sangria.ast.Document
import com.openbank.dwh.model._
import com.openbank.dwh.persistence._
import sangria.execution.Executor
import sangria.parser.QueryParser
import com.typesafe.scalalogging.LazyLogging
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import scala.util.{Failure, Success}


// https://github.com/sangria-graphql/sangria-subscriptions-example/blob/master/src/main/scala/Server.scala
class GraphQLService(graphStorage: SecondaryPersistence)(implicit ec: ExecutionContext) extends SprayJsonSupport with LazyLogging {

  import sangria.marshalling.sprayJson._
  import spray.json._

  private lazy val executor = Executor(SchemaDefinition.GraphQLSchema, deferredResolver = SchemaDefinition.Resolver)

  def execute(query: String, operation: Option[String], variables: JsObject = JsObject.empty): Future[JsValue] = {
    QueryParser.parse(query) match {

      case Success(queryAst) =>
        executor
          .execute(queryAst, graphStorage, (), operation, variables)

      case Failure(error) =>
        Future.failed(error)

    }
  }

}
