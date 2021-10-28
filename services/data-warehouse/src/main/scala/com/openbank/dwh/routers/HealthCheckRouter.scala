package com.openbank.dwh.routers

import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import com.openbank.dwh.service.HealthCheckService
import spray.json._
import scala.concurrent.ExecutionContext

class HealthCheckRouter(service: HealthCheckService) extends SprayJsonSupport {

  def route: Route =
    path("health") {
      get {
        onSuccess(service.isGraphQLHealthy()(ExecutionContext.global)) { isGraphQlHealthy =>
          complete(
            JsObject(
              "healthy" -> JsBoolean(isGraphQlHealthy),
              "graphql" -> JsBoolean(isGraphQlHealthy)
            )
          )
        }
      }
    }
}
