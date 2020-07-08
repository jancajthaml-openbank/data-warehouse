package com.openbank.dwh.routers

import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import com.openbank.dwh.service.GraphQLService
import sangria.parser.{QueryParser, SyntaxError}

import akka.http.scaladsl.model.StatusCodes._
import java.nio.charset.Charset
import akka.http.scaladsl.marshalling.{Marshaller, ToEntityMarshaller}
import akka.http.scaladsl.model._
import akka.http.scaladsl.server._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.model.headers.Accept
import akka.http.scaladsl.unmarshalling.{FromEntityUnmarshaller, Unmarshaller}
import akka.util.ByteString
import sangria.execution.{ErrorWithResolver, QueryAnalysisError}
import sangria.ast.Document
import sangria.parser.QueryParser
import scala.util.control.NonFatal
import scala.collection.immutable.Seq
import scala.util.Try
import scala.concurrent.ExecutionContext


class GraphQLRouter(service: GraphQLService) extends SprayJsonSupport {

  import spray.json._
  import sangria.marshalling.sprayJson._
  import akka.http.scaladsl.marshalling.sse.EventStreamMarshalling._

  val route: Route =
    path("graphql") {
      post {
        entity(as[JsValue]) { requestJson =>
          val JsObject(fields) = requestJson
          val JsString(query) = fields("query")
          val operation = fields
            .get("operationName")
            .collect { case JsString(op) => op }
          val vars = fields
            .get("variables") match {
              case Some(obj: JsObject) => obj
              case _ => JsObject.empty
            }

          complete(service.execute(query, operation, vars))
        }
      } ~
      get {
        parameters('query, 'operation.?) { (query, operation) ⇒
          complete(service.execute(query, operation))
        }
      }
    } ~
    pathPrefix("graphiql") {
      pathPrefix("static") {
        getFromResourceDirectory("graphiql/static")
      } ~
      pathEndOrSingleSlash {
        getFromResource("graphiql/index.html")
      }
    }

}
