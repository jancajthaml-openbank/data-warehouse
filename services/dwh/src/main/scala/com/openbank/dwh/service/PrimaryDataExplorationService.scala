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
import java.util.concurrent.atomic.AtomicLong

// https://www.youtube.com/watch?v=nncxYGD6m7E
// https://doc.akka.io/docs/akka/current/stream/stream-composition.html
// https://github.com/inanna-malick/akka-streams-example
// https://blog.colinbreck.com/maximizing-throughput-for-akka-streams/
// https://blog.colinbreck.com/partitioning-akka-streams-to-maximize-throughput/


class PrimaryDataExplorationService(primaryStorage: PrimaryPersistence, secondaryStorage: SecondaryPersistence)(implicit ec: ExecutionContext, implicit val mat: Materializer) extends LazyLogging {

  private val lastModTime = new AtomicLong(0L)

  def isStoragePristine(): Boolean = {
    val nextModTime = primaryStorage.getLastModificationTime()
    if (lastModTime.longValue() < nextModTime) {
      lastModTime.set(nextModTime)
      return false
    } else {
      return true
    }
  }

  def exploreAccounts(): Future[Done] = {
    logger.debug("Exploring accounts from Primary data source")

    Source
      .single(primaryStorage.getRootPath())
      .via(getTenansFlow)
      .via(getAccountsFlow)
      .runWith(Sink.ignore)
  }

  def exploreTransfers(): Future[Done] = {
    logger.debug("Exploring transfers from Primary data source")

    Source
      .single(primaryStorage.getRootPath())
      .via(getTenansFlow)
      .via(getAccountsFlow)
      .via(getAccountSnapshotsFlow)
      .via(getAccountEventsFlow)
      .via(getTransfersFlow)
      .runWith(Sink.ignore)
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
      .mapAsyncUnordered(10) {
        case tenant if tenant.isPristine =>
          Future.successful(tenant)
        case tenant => {
          lastModTime.set(0L)
          secondaryStorage
            .updateTenant(tenant)
            .map(_ => tenant)
        }
      }
      .async
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
      .mapAsync(1) {
        case (tenant, account) if account.isPristine =>
          Future.successful((tenant, account))
        case (tenant, account) => {
          secondaryStorage
            .updateAccount(account)
            .map { _ =>
              lastModTime.set(0L)
              (tenant, account)
            }
        }
      }
      .async
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
          .take(2)
          .map { version => (tenant, account, version) }
      }
      .mapConcat(_.to[Seq])
      .mapAsync(100) { case (tenant, account, version) => {
        primaryStorage
          .getAccountSnapshot(tenant.name, account.name, version)
          .map(_.map { snapshot => (tenant, account, snapshot) })
      }}
      .async
      .collect { case Some(data) => data }
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
      .mapAsync(1000) { events =>
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
      }
      .async
      .mapConcat(_.to[Seq])
      .buffer(10000, OverflowStrategy.backpressure)
  }

  def getTransfersFlow: Graph[FlowShape[Tuple4[Tenant, Account, AccountSnapshot, AccountEvent], Tuple5[Tenant, Account, AccountSnapshot, AccountEvent, Seq[Transfer]]], NotUsed] = {
    Flow[Tuple4[Tenant, Account, AccountSnapshot, AccountEvent]]
      .mapAsync(100) {
        case (tenant, account, snapshot, event) if event.status == 1 => // FIXME enum
          primaryStorage
            .getTransfers(tenant.name, event.transaction)
            .map(_.filter { transfer =>
              (transfer.creditTenant == tenant.name && transfer.creditAccount == account.name) ||
              (transfer.debitTenant == tenant.name && transfer.debitAccount == account.name)
            })
            .map { transfers => (tenant, account, snapshot, event, transfers) }
        case (tenant, account, snapshot, event) =>
          Future.successful((tenant, account, snapshot, event, Seq.empty[Transfer]))
      }
      .async
      .mapAsync(1000) {
        case (tenant, account, snapshot, event, transfers) if transfers.isEmpty => {
          Future.successful {
            (
              tenant,
              account.copy(
                lastSynchronizedSnapshot = snapshot.version,
                lastSynchronizedEvent = event.version,
                isPristine = false
              ),
              snapshot,
              event,
              transfers
            )
          }
        }

        case (tenant, account, snapshot, event, transfers) => {
          Future.sequence {
            transfers
              .map { transfer =>
                secondaryStorage
                  .updateTransfer(transfer)
                  .map { _ =>
                    lastModTime.set(0L)
                    transfer
                  }
              }
          }
            .map { transfers =>
              (
                tenant,
                account.copy(
                  lastSynchronizedSnapshot = snapshot.version,
                  lastSynchronizedEvent = event.version,
                  isPristine = false
                ),
                snapshot,
                event,
                transfers
              )
            }
        }
      }
      .async
      .mapAsync(1) {
        case (tenant, account, snapshot, event, transfers) if account.isPristine =>
          Future.successful((tenant, account, snapshot, event, transfers))
        case (tenant, account, snapshot, event, transfers) => {
          lastModTime.set(0L)
          secondaryStorage
            .updateAccount(account)
            .map { _ => (tenant, account, snapshot, event, transfers) }
        }
      }
      .async
      .map {
        case (tenant, account, snapshot, event, transfers) =>
          (tenant, account, snapshot, event, transfers)
      }
  }

}
