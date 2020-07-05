package com.openbank.dwh.service

import java.nio.file.{Paths, Files, Path}
import akka.Done
import com.typesafe.scalalogging.LazyLogging
import scala.concurrent.{ExecutionContext, Future}
import scala.collection.mutable.Builder
import scala.collection.generic.CanBuildFrom
import language.higherKinds
import akka.stream.scaladsl._
import akka.stream.{Materializer, OverflowStrategy}
import com.openbank.dwh.model._
import com.openbank.dwh.persistence._
import collection.immutable.Seq

// https://blog.colinbreck.com/maximizing-throughput-for-akka-streams/
// https://blog.colinbreck.com/partitioning-akka-streams-to-maximize-throughput/

class PrimaryDataExplorationService(primaryStorage: PrimaryPersistence)(implicit ec: ExecutionContext, implicit val mat: Materializer) extends LazyLogging {

  sealed trait Discovery
  case class TenantDiscovery(name: String) extends Discovery
  case class AccountDiscovery(tenant: String, name: String) extends Discovery
  case class AccountSnapshotDiscovery(tenant: String, account: String, version: Int) extends Discovery
  case class AccountEventDiscovery(tenant: String, account: String, version: Int, event: String) extends Discovery

  def runExploration: Future[Done] = {
    //.buffer(1, OverflowStrategy.backpressure)

    Source
      .single(primaryStorage.getRootPath())
      .via {
        Flow[Path]
          .map { path =>
            path
              .toFile
              .listFiles(_.getName.matches("t_.+"))
              .map(_.getName.stripPrefix("t_"))
              .map { name => TenantDiscovery(name) }
          }
          .mapConcat(_.to[Seq])
          .mapAsync(10)(onTenantDiscovery)
          .async
          .recover { case e: Exception => None }
          .collect { case Some(tenant) => tenant }
      }
      .via {
        Flow[Tenant]
          .map { tenant =>
            (tenant, primaryStorage.getAccountsPath(tenant.name))
          }
      }
      .via {
        Flow[Tuple2[Tenant, Path]]
          .map { case (tenant, path) =>
            path
              .toFile
              .listFiles()
              .map(_.getName)
              .map { name => (tenant, AccountDiscovery(tenant.name, name)) }
          }
          .mapConcat(_.to[Seq])
          .mapAsyncUnordered(100) { case (tenant, discovery) =>
            onAccountDiscovery(discovery)
              .map(_.map { account => (tenant, account) })
          }
          .async
          .recover { case e: Exception => None }
          .collect { case Some(data) => data }
      }
      .via {
        Flow[Tuple2[Tenant, Account]]
          .map { case (tenant, account) =>
            (tenant, account, primaryStorage.getAccountSnapshotsPath(account.tenant, account.name))
          }
      }
      .via {
        Flow[Tuple3[Tenant, Account, Path]]
          .map { case (tenant, account, path) =>
            path
              .toFile
              .listFiles()
              .map(_.getName.toInt)
              .filter(_ >= account.lastSynchronizedSnapshot)
              .sortWith(_ < _)
              .map { version => (tenant, account, AccountSnapshotDiscovery(account.tenant, account.name, version)) }
          }
          .mapConcat(_.to[Seq])
          .mapAsync(10) { case (tenant, account, discovery) =>
            onAccountSnapshotDiscovery(discovery)
              .map(_.map { snapshot => (tenant, account, snapshot) })
          }
          .async
          .recover { case e: Exception => None }
          .collect { case Some(data) => data }
      }
      .via {
        Flow[Tuple3[Tenant, Account, AccountSnapshot]]
          .map { case (tenant, account, snapshot) =>
            (tenant, account, snapshot, primaryStorage.getAccountEventsPath(account.tenant, account.name, snapshot.version))
          }
      }
      .via {
        Flow[Tuple4[Tenant, Account, AccountSnapshot, Path]]
          .map {
            case (tenant, account, snapshot, path) =>
              path
                .toFile
                .listFiles()
                .map(_.getName)
                .map { file => (tenant, account, snapshot, AccountEventDiscovery(account.tenant, account.name, snapshot.version, file)) }
          }
          .filterNot { events =>
            events.isEmpty ||
            (
              events.last._2.lastSynchronizedSnapshot == events.last._3.version &&
              events.size <= events.last._3.lastSynchronizedEvent
            )
          }
          .mapConcat(_.to[Seq])
          .mapAsync(10) { case (tenant, account, snapshot, discovery) =>
            onAccountEventDiscovery(discovery)
              .map(_.map { event => (tenant, account, snapshot, event) })
          } // FIXME after version is known need to sort by version ascending like ".sortWith(_._4.version < _._4.version)"
          .async
          .recover { case e: Exception => None }
          .collect { case Some(data) => data }
      }
      .via {
        Flow[Tuple4[Tenant, Account, AccountSnapshot, AccountEvent]]
          .map {
            case (tenant, account, snapshot, event) =>
              (tenant, account, snapshot, event)
          }
      }
      .runWith(Sink.ignore)
      .map(_ => Done)

      // FIXME now parition events,
      // status == 1 are committed and one is able to discover transactions from that
      // other statuses are only useful for event version id (in given snapshot)
      // to update account pivoting
      /*
      .via {
        Partition[Tuple4[Tenant, Account, AccountSnapshot, AccountEvent]](2,
          case data if data._4.status == 1 => 0
          case data => 1
        )
      }
      */

      // custom buffer
      // https://stackoverflow.com/questions/44656618/akka-stream-sort-by-id-in-java

      // FIXME next step get events for each account snapshot
  }

  // FIXME rename to "acknowledge" something...
  private def onTenantDiscovery(item: TenantDiscovery): Future[Option[Tenant]] = {
    // FIXME now ask (call) secondary storage for Tenant entity, if it returns
    // None tell SecondaryDataPersistorActor about this tenant existence

    val result = primaryStorage.getTenant(item.name)

    // FIXME check if tenant has account and transaction subfolders

    result.map { data =>
      data.foreach { tenant =>
        logger.info(s"explored tenant ${item} as ${tenant}")
      }
      data
    }
  }

  // FIXME rename to "acknowledge" something...
  private def onAccountDiscovery(item: AccountDiscovery): Future[Option[Account]] = {

    // FIXME now ask (call) secondary storage for Account entity, if it returns
    // None get it from primary storage

    val result = primaryStorage.getAccountMetaData(item.tenant, item.name)

    // FIXME if whatever we end up with is different than what we obtained from
    // primary storage (if necessary) tell SecondaryDataPersistorActor about this
    // account existence

    result.map { data =>
      data.foreach { account =>
        logger.info(s"explored account ${account}")
      }
      data
    }
  }

  // FIXME rename to "acknowledge" something...
  private def onAccountSnapshotDiscovery(item: AccountSnapshotDiscovery): Future[Option[AccountSnapshot]] = {
    val result = primaryStorage.getAccountSnapshot(item.tenant, item.account, item.version)

    result.map { data =>
      data.foreach { snapshot =>
        logger.info(s"explored account snapshot ${snapshot}")
      }
      data
    }
  }

  // FIXME rename to "acknowledge" something...
  private def onAccountEventDiscovery(item: AccountEventDiscovery): Future[Option[AccountEvent]] = {
    val result = primaryStorage.getAccountEvent(item.tenant, item.account, item.version, item.event)

    result.map { data =>
      data.foreach { event =>
        logger.info(s"explored account event ${event}")
      }
      data
    }
  }

}
