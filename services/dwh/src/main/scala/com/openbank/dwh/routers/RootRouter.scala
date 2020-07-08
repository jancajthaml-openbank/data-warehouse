package com.openbank.dwh.routers

import sangria.parser.{QueryParser, SyntaxError}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import sangria.execution.{ErrorWithResolver, QueryAnalysisError}
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.{ExceptionHandler, Route}
import scala.language.implicitConversions
import akka.stream.Attributes.LogLevels


class RootRouter(routes: Route*) extends SprayJsonSupport {

  import spray.json._
  import sangria.marshalling.sprayJson._

  def route: Route = logRequestResult("access-debug", LogLevels.Debug) {
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
      complete(BadRequest -> JsObject(
        "syntaxError" -> JsString(e.getMessage),
        "locations" -> JsArray(JsObject(
          "line" -> JsNumber(e.originalError.position.line),
          "column" -> JsNumber(e.originalError.position.column)
        ))
      ))
    case _ =>
      complete(InternalServerError)
  }

}
