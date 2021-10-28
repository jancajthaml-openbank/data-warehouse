package com.openbank.dwh.routers

import sangria.parser.SyntaxError
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import sangria.execution.{ErrorWithResolver, QueryAnalysisError}
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.{ExceptionHandler, Route}
import akka.stream.Attributes.LogLevels
import spray.json.{JsArray, JsNumber, JsObject, JsString}
import sangria.marshalling.sprayJson._

class RootRouter(routes: Route*) extends SprayJsonSupport {

  def route: Route =
    logRequestResult(("access", LogLevels.Debug)) {
      handleExceptions(exceptionHandler) {
        concat(routes: _*)
      }
    }

  private val exceptionHandler = ExceptionHandler {
    case e: QueryAnalysisError =>
      complete(BadRequest -> e.resolveError)
    case e: ErrorWithResolver =>
      complete(InternalServerError -> e.resolveError)
    case e: SyntaxError =>
      complete(
        BadRequest -> JsObject(
          "syntaxError" -> JsString(e.getMessage),
          "locations" -> JsArray(
            JsObject(
              "line" -> JsNumber(e.originalError.position.line),
              "column" -> JsNumber(e.originalError.position.column)
            )
          )
        )
      )
    case _ =>
      complete(InternalServerError)
  }

}
