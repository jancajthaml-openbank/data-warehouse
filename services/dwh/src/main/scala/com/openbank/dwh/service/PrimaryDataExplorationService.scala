package com.openbank.dwh.service

import java.nio.file.{Paths, Files, Path}
import akka.Done
import akka.NotUsed
import com.typesafe.scalalogging.LazyLogging
import scala.concurrent.{ExecutionContext, Future}
import scala.collection.mutable.Builder
import scala.collection.generic.CanBuildFrom
import language.higherKinds
import akka.stream.scaladsl._
import akka.stream.{Materializer, OverflowStrategy, FlowShape, Graph, ClosedShape}
import com.openbank.dwh.model._
import com.openbank.dwh.persistence._
import collection.immutable.Seq

// https://www.youtube.com/watch?v=nncxYGD6m7E
// https://doc.akka.io/docs/akka/current/stream/stream-composition.html
// https://github.com/inanna-malick/akka-streams-example
// https://blog.colinbreck.com/maximizing-throughput-for-akka-streams/
// https://blog.colinbreck.com/partitioning-akka-streams-to-maximize-throughput/

class PrimaryDataExplorationService(primaryStorage: PrimaryPersistence)(implicit ec: ExecutionContext, implicit val mat: Materializer) extends LazyLogging {

  def runExploration: Future[Done] = {
    //.buffer(1, OverflowStrategy.backpressure)

    val source = Source.single(primaryStorage.getRootPath())
    val sink = Sink.ignore
    val tenants = getTenansFlow
    val accounts = getAccountsFlow
    val snapshots = getAccountSnapshotsFlow
    val events = getAccountEventsFlow

    val graph = RunnableGraph.fromGraph(GraphDSL.create(sink) { implicit builder => term =>
      import GraphDSL.Implicits._

      val partitionEvents = builder.add(Partition[Tuple4[Tenant, Account, AccountSnapshot, AccountEvent]](2, _ match {
        case (tenant, account, snapshot, event) if event.status == 1 => 0
        case _ => 1
      }))

      val mergeEvents = builder.add(Merge[Tuple4[Tenant, Account, AccountSnapshot, AccountEvent]](2))

      source ~> tenants ~> accounts ~> snapshots ~> events ~> partitionEvents

      // events that needs to be exploded into transactions
      partitionEvents.out(0) ~> mergeEvents

      partitionEvents.out(1) ~> mergeEvents

      // FIXME after merge events update account snapshot+event
      mergeEvents ~> term.in

      ClosedShape
    })

    graph
      .run()
      .map(_ => Done)
  }

  def getTenansFlow: Graph[FlowShape[Path, Tenant], NotUsed] = {
    Flow[Path]
      .map { path =>
        path
          .toFile
          .listFiles(_.getName.matches("t_.+"))
          .map(_.getName.stripPrefix("t_"))
      }
      .mapConcat(_.to[Seq])
      .mapAsyncUnordered(10) { name =>
        primaryStorage.getTenant(name)
      }
      .async
      .recover { case e: Exception => None }
      .collect { case Some(tenant) => tenant }
      .log("tenants")
  }

  def getAccountsFlow: Graph[FlowShape[Tenant, Tuple2[Tenant, Account]], NotUsed] = {
    Flow[Tenant]
      .map { tenant =>
        (tenant, primaryStorage.getAccountsPath(tenant.name))
      }
      .map { case (tenant, path) =>
        path
          .toFile
          .listFiles()
          .map(_.getName)
          .map { name => (tenant, name) }
      }
      .mapConcat(_.to[Seq])
      .mapAsyncUnordered(100) { case (tenant, name) => {
        // FIXME check secondary storage
        primaryStorage
          .getAccountMetaData(tenant.name, name)
          .map(_.map { account => (tenant, account) })
      }}
      .async
      .recover { case e: Exception => None }
      .collect { case Some(data) => data }
      .log("accounts")
  }

  def getAccountSnapshotsFlow: Graph[FlowShape[Tuple2[Tenant, Account], Tuple3[Tenant, Account, AccountSnapshot]], NotUsed] = {
    Flow[Tuple2[Tenant, Account]]
      .map { case (tenant, account) =>
        (tenant, account, primaryStorage.getAccountSnapshotsPath(account.tenant, account.name))
      }
      .map { case (tenant, account, path) =>
        path
          .toFile
          .listFiles()
          .map(_.getName.toInt)
          .filter(_ >= account.lastSynchronizedSnapshot)
          .sortWith(_ < _)
          .map { version => (tenant, account, version) }
      }
      .mapConcat(_.to[Seq])
      .mapAsync(10) { case (tenant, account, version) => {
        // FIXME try to retrieve state from secondary storage
        primaryStorage
          .getAccountSnapshot(tenant.name, account.name, version)
          .map(_.map { snapshot => (tenant, account, snapshot) })
      }}
      .async
      .recover { case e: Exception => None }
      .collect { case Some(data) => data }
      .log("snapshots")
  }

  def getAccountEventsFlow: Graph[FlowShape[Tuple3[Tenant, Account, AccountSnapshot], Tuple4[Tenant, Account, AccountSnapshot, AccountEvent]], NotUsed] = {
    Flow[Tuple3[Tenant, Account, AccountSnapshot]]
      .map { case (tenant, account, snapshot) =>
        (tenant, account, snapshot, primaryStorage.getAccountEventsPath(account.tenant, account.name, snapshot.version))
      }
      .map {
        case (tenant, account, snapshot, path) =>
          path
            .toFile
            .listFiles()
            .map(_.getName)
            .map { file => (tenant, account, snapshot, file) }
      }
      .filterNot { events =>
        events.isEmpty ||
        (
          events.last._2.lastSynchronizedSnapshot == events.last._3.version &&
          events.size <= events.last._3.lastSynchronizedEvent
        )
      }
      .mapAsync(1) { events =>
        Future.sequence {
          events
            .toSeq
            .map { case (tenant, account, snapshot, event) =>
              primaryStorage
                .getAccountEvent(tenant.name, account.name, snapshot.version, event)
                .map(_.map { event => (tenant, account, snapshot, event) })
            }
        }
        .map(_.flatten)
        .map(_.sortWith(_._4.version < _._4.version))
        .recover { case e: Exception => Seq.empty[Tuple4[Tenant, Account, AccountSnapshot, AccountEvent]] }
        // FIXME not sequence empty onrecover instead filter all non continuous events
        // So for example if my last sync event is 2 and I captured events from 3-10 and then 12-20 I can
        // resolve 3-10 and drop the rest because then I can guarantee continous history
      }
      .async
      .mapConcat(_.to[Seq])
      .log("events")
  }

}
