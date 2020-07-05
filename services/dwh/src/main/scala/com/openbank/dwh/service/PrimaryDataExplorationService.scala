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

class PrimaryDataExplorationService(primaryStorage: PrimaryPersistence, secondaryStorage: SecondaryPersistence)(implicit ec: ExecutionContext, implicit val mat: Materializer) extends LazyLogging {

  def runExploration: Future[Done] = {
    val source = Source.single(primaryStorage.getRootPath())
    val sink = Sink.ignore
    val graph = RunnableGraph.fromGraph(GraphDSL.create(sink) { implicit builder => term =>
      import GraphDSL.Implicits._

      val tenants = builder.add(getTenansFlow)
      val accounts = builder.add(getAccountsFlow)
      val snapshots = builder.add(getAccountSnapshotsFlow)
      val events = builder.add(getAccountEventsFlow)
      val transactions = builder.add(getTransactionFlow)

      val tenantFork = builder.add(Broadcast[Tenant](2))
      val accountFork = builder.add(Broadcast[Tuple2[Tenant, Account]](2))
      val termFork = builder.add(Broadcast[Account](2))

      source ~> tenants ~> tenantFork

      tenantFork ~> onTenantFlow
      tenantFork ~> accounts ~> accountFork

      accountFork ~> Flow[Tuple2[Tenant, Account]].map(_._2) ~> onAccountFlow
      accountFork ~> snapshots ~> events ~> transactions

      transactions ~> Flow[Tuple5[Tenant, Account, AccountSnapshot, AccountEvent, Option[Transaction]]]
        .map { case (tenant, account, snapshot, event, transaction) =>
          account.copy(
            lastSynchronizedSnapshot = snapshot.version,
            lastSynchronizedEvent = event.version,
            isPristine = false
          )
        } ~> termFork

      termFork ~> onAccountFlow
      termFork ~> term.in

      ClosedShape
    })

    graph
      .run()
      .map(_ => Done)
  }

  def onTenantFlow: Sink[Tenant, NotUsed] = {
    Flow[Tenant]
      .filterNot(_.isPristine)
      .log("tenant")
      .mapAsyncUnordered(10)(secondaryStorage.updateTenant)
      .async
      .recover { case e: Exception => Done }
      .to(Sink.ignore)
  }

  def onAccountFlow: Sink[Account, NotUsed] = {
    Flow[Account]
      .filterNot(_.isPristine)
      .log("account")
      .mapAsync(1)(secondaryStorage.updateAccount)
      .async
      .recover { case e: Exception => Done }
      .to(Sink.ignore)
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
        secondaryStorage
          .getTenant(name)
          .flatMap {
            case None => primaryStorage.getTenant(name)
            case tenant => Future.successful(tenant)
          }
      }
      .async
      .recover { case e: Exception => None }
      .collect { case Some(tenant) => tenant }
  }

  def getAccountsFlow: Graph[FlowShape[Tenant, Tuple2[Tenant, Account]], NotUsed] = {
    Flow[Tenant]
      .map { tenant =>
        primaryStorage
          .getAccountsPath(tenant.name)
          .toFile
          .listFiles()
          .map { file => (tenant, file.getName) }
      }
      .mapConcat(_.to[Seq])
      .mapAsyncUnordered(10) { case (tenant, name) => {
        secondaryStorage
          .getAccount(tenant.name, name)
          .flatMap {
            case None => primaryStorage.getAccount(tenant.name, name)
            case account => Future.successful(account)
          }
          .map(_.map { account => (tenant, account) })
      }}
      .async
      .recover { case e: Exception => None }
      .collect { case Some(data) => data }
  }

  def getAccountSnapshotsFlow: Graph[FlowShape[Tuple2[Tenant, Account], Tuple3[Tenant, Account, AccountSnapshot]], NotUsed] = {
    Flow[Tuple2[Tenant, Account]]
      .map { case (tenant, account) =>
        primaryStorage
          .getAccountSnapshotsPath(account.tenant, account.name)
          .toFile
          .listFiles()
          .map(_.getName.toInt)
          .filter(_ >= account.lastSynchronizedSnapshot)
          .sortWith(_ < _)
          .map { version => (tenant, account, version) }
      }
      .mapConcat(_.to[Seq])
      .mapAsync(10) { case (tenant, account, version) => {
        primaryStorage
          .getAccountSnapshot(tenant.name, account.name, version)
          .map(_.map { snapshot => (tenant, account, snapshot) })
      }}
      .async
      .recover { case e: Exception => None }
      .collect {
        case Some(data) => data
      }
  }

  def getAccountEventsFlow: Graph[FlowShape[Tuple3[Tenant, Account, AccountSnapshot], Tuple4[Tenant, Account, AccountSnapshot, AccountEvent]], NotUsed] = {
    Flow[Tuple3[Tenant, Account, AccountSnapshot]]
      .map { case (tenant, account, snapshot) =>
        primaryStorage
          .getAccountEventsPath(account.tenant, account.name, snapshot.version)
          .toFile
          .listFiles()
          .map { file => (tenant, account, snapshot, file.getName) }
      }
      .filterNot { events =>
        events.isEmpty ||
        (
          events.last._2.lastSynchronizedSnapshot == events.last._3.version &&
          events.last._2.lastSynchronizedEvent >= events.size
        )
      }
      .mapAsync(100) { events =>
        Future.sequence {
          events
            .toSeq
            .map { case (tenant, account, snapshot, event) =>
              primaryStorage
                .getAccountEvent(tenant.name, account.name, snapshot.version, event)
                .map(_.map { event => (tenant, account, snapshot, event) })
            }
        }
        .map(_.flatten.sortWith(_._4.version < _._4.version))
        .recover { case e: Exception => Seq.empty[Tuple4[Tenant, Account, AccountSnapshot, AccountEvent]] }
        // FIXME not sequence empty onrecover instead filter all non continuous events
        // So for example if my last sync event is 2 and I captured events from 3-10 and then 12-20 I can
        // resolve 3-10 and drop the rest because then I can guarantee continous history
      }
      .async
      .mapConcat(_.to[Seq])
      .buffer(1000, OverflowStrategy.backpressure)
  }

  def getTransactionFlow: Graph[FlowShape[Tuple4[Tenant, Account, AccountSnapshot, AccountEvent], Tuple5[Tenant, Account, AccountSnapshot, AccountEvent, Option[Transaction]]], NotUsed] = {
    Flow[Tuple4[Tenant, Account, AccountSnapshot, AccountEvent]]
      .map {
        case (tenant, account, snapshot, event) if event.status == 1 =>
          (tenant, account, snapshot, event, Some(Transaction(tenant.name, "???")))
        case (tenant, account, snapshot, event) =>
          (tenant, account, snapshot, event, None)
      }
      //.log("transaction")
  }

}
