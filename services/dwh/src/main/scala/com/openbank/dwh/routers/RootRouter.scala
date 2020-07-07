package com.openbank.dwh.routers

import sangria.parser.{QueryParser, SyntaxError}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import sangria.execution.{ErrorWithResolver, QueryAnalysisError}
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.{ExceptionHandler, Route}
import scala.util.control.NonFatal
import scala.language.implicitConversions


class RootRouter(routes: Route*) extends SprayJsonSupport {

  import spray.json._
  import sangria.marshalling.sprayJson._

  def route: Route =
    handleExceptions(exceptionHandler) {
      concat(routes: _*)
    }

  private val exceptionHandler = ExceptionHandler {
    case e: QueryAnalysisError =>
      complete(BadRequest -> e.resolveError)
    case e: ErrorWithResolver =>
      complete(InternalServerError -> e.resolveError)
    case _ =>
      complete(InternalServerError)
  }

}
