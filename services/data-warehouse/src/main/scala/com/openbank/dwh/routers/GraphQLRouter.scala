package com.openbank.dwh.routers

import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import com.openbank.dwh.service.GraphQLService
import akka.http.scaladsl.server._
import akka.http.scaladsl.server.Directives._

class GraphQLRouter(service: GraphQLService) extends SprayJsonSupport {

  import spray.json._

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
            case _                   => JsObject.empty
          }

          complete(service.execute(query, operation, vars))
        }
      } ~
        get {
          parameters(Symbol("query"), Symbol("operation").?) {
            (query, operation) =>
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
