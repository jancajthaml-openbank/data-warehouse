package com.openbank.dwh.routers

import spray.json._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.{ExceptionHandler, Route}
import scala.util.control.NonFatal
import com.typesafe.scalalogging.StrictLogging
import scala.language.implicitConversions


class RootRouter(routes: Route*) extends SprayJsonSupport with StrictLogging {

  def route: Route =
    handleExceptions(exceptionHandler) {
      concat(routes: _*)
    }

  private val exceptionHandler = ExceptionHandler {
    case _ =>
      complete(InternalServerError)
  }

}
