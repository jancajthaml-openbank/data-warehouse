package com.openbank.dwh.utils

import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, Materializer}
import akka.testkit.TestKit
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterAll


class AkkaSpecBase(name: String) extends TestKit(ActorSystem(name)) with AnyFlatSpecLike with Matchers with BeforeAndAfterAll {

  override protected def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
    super.afterAll()
  }

  implicit val mat: Materializer = ActorMaterializer()(system)
}
