package com.openbank.dwh.service

import scala.concurrent.{ExecutionContext, Future}
import com.openbank.dwh.model.GraphQL
import com.openbank.dwh.persistence._
import sangria.execution.{ExceptionHandler, Executor, HandledException}
import sangria.parser.QueryParser
import com.typesafe.scalalogging.StrictLogging
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport


// https://github.com/sangria-graphql/sangria-subscriptions-example/blob/master/src/main/scala/Server.scala
class GraphQLService(graphStorage: GraphQLPersistence)(implicit ec: ExecutionContext) extends SprayJsonSupport with StrictLogging {

  import sangria.marshalling.sprayJson._
  import spray.json._

  private val executor = Executor(
    schema = GraphQL.GraphSchema,
    exceptionHandler = exceptionHandler,
    deferredResolver = GraphQL.Resolver
  )

  private lazy val exceptionHandler = ExceptionHandler {
    case (m, e) =>
      logger.error(s"exception occured m: ${m} e: ${e}")
      HandledException("Internal server error")
  }

  def execute(query: String, operation: Option[String], variables: JsObject = JsObject.empty): Future[JsValue] = {
    Future
      .fromTry {
        QueryParser.parse(query)
      }
      .flatMap { queryAst =>
        executor
          .execute(queryAst, graphStorage, (), operation, variables)
      }
  }

}
