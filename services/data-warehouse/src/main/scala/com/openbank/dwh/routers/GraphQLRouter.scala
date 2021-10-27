package com.openbank.dwh.routers

import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.StatusCodes.BadRequest
import com.openbank.dwh.service.GraphQLService
import spray.json.{JsArray, JsObject, JsString, JsValue}
import scala.concurrent.ExecutionContext
import scala.util.{Success, Try}

class GraphQLRouter(service: GraphQLService) extends SprayJsonSupport {

  val route: Route =
    path("graphql") {
      post {
        entity(as[JsValue]) { requestJson =>
          val extract: JsObject => (Option[String], Option[String], JsObject) =
            query =>
              (
                query.fields.get("query") match {
                  case Some(JsString(value)) => Some(value)
                  case _                     => None
                },
                query.fields.get("operationName") match {
                  case Some(JsString(value)) => Some(value)
                  case _                     => None
                },
                query.fields.get("variables") match {
                  case Some(obj: JsObject) => obj
                  case _                   => JsObject.empty
                }
              )

          Try {
            requestJson match {
              case arrayBody @ JsArray(_) =>
                extract(arrayBody.elements(0).asJsObject)
              case objectBody @ JsObject(_) =>
                extract(objectBody)
              case otherType =>
                throw new Error(
                  s"The '/graphql' endpoint doesn't support a request body of the type [${otherType.getClass.getSimpleName}]"
                )
            }
          } match {
            case Success((Some(query), operationName, variables)) =>
              complete(
                service.execute(query, operationName, variables)(
                  ExecutionContext.global
                )
              )
            case _ =>
              complete(BadRequest)
          }
        }
      } ~
        get {
          parameters(Symbol("query"), Symbol("operation").?) {
            (query, operation) =>
              complete(
                service.execute(query, operation)(ExecutionContext.global)
              )
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
