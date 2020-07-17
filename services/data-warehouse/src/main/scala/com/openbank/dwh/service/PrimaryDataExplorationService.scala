package com.openbank.dwh.service

import java.nio.file.{Paths, Files, Path}
import akka.Done
import akka.NotUsed
import com.typesafe.scalalogging.StrictLogging
import scala.concurrent.{ExecutionContext, Future}
import scala.collection.mutable.Builder
import scala.collection.generic.CanBuildFrom
import language.higherKinds
import akka.stream.scaladsl._
import akka.stream._
import com.openbank.dwh.model._
import com.openbank.dwh.persistence._
import collection.immutable.Seq
import java.util.concurrent.atomic.AtomicLong


class PrimaryDataExplorationService(primaryStorage: PrimaryPersistence, secondaryStorage: SecondaryPersistence)(implicit ec: ExecutionContext, implicit val mat: Materializer) extends StrictLogging {

  private lazy val parallelism = 2

  @volatile private var killSwitch: Option[UniqueKillSwitch] = None

  def killRunningWorkflow(): Future[Done] = {
    killSwitch.foreach(_.shutdown())
    killSwitch = None
    Future.successful(Done)
  }

  private val lastModTime = new AtomicLong(0L)

  private def markAsDirty() = lastModTime.set(0L)

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
    val (switch, result) = Source
      .single(primaryStorage.getRootPath())
      .via(getTenansFlow)
      .via(getAccountsFlow)
      .viaMat(KillSwitches.single)(Keep.right)
      .toMat(Sink.ignore)(Keep.both)
      .run()

    killSwitch = Some(switch)
    result
  }

  // zipWithN

  def exploreTransfers(): Future[Done] = {
    val (switch, result) = Source
      .fromPublisher(secondaryStorage.getTenants())
      .flatMapConcat { tenant =>
        Source.fromPublisher(secondaryStorage.getAccounts(tenant.name))
      }
      .via(getAccountSnapshotsFlow)
      .via(getAccountEventsFlow)
      .via(getTransfersFlow)
      .viaMat(KillSwitches.single)(Keep.right)
      .toMat(Sink.ignore)(Keep.both)
      .run()

    killSwitch = Some(switch)

    result
  }

  def getTenansFlow: Graph[FlowShape[Path, PersistentTenant], NotUsed] = {
    Flow[Path]
      .flatMapConcat { path =>
        Source {
          path
            .toFile
            .listFiles(_.getName.matches("t_.+"))
            .map(_.getName.stripPrefix("t_"))
            .toIndexedSeq
        }
      }
      .buffer(parallelism * 2, OverflowStrategy.backpressure)
      .mapAsync(parallelism) { name =>
        (
          primaryStorage.getTenant(name)
          zip
          secondaryStorage.getTenant(name)
        ).flatMap {
          case (None, None) =>
            Future.successful(None)
          case (None, Some(b)) =>
            Future.successful(Some(b))
          case (Some(a), None) =>
            secondaryStorage
              .updateTenant(a)
              .map { _ =>
                markAsDirty()
                Some(a)
              }
          case (Some(a), Some(_)) =>
            Future.successful(Some(a))
        }
      }
      .async
      .recover { case e: Exception =>
        logger.warn("Failed to get tenant caused by", e)
        None
      }
      .collect { case Some(tenant) => tenant }
      .buffer(parallelism * 2, OverflowStrategy.backpressure)
  }

  def getAccountsFlow: Graph[FlowShape[PersistentTenant, PersistentAccount], NotUsed] = {
    Flow[PersistentTenant]
      .flatMapConcat { tenant =>
        Source {
          primaryStorage
            .getAccountsPath(tenant.name)
            .toFile
            .listFiles()
            .map { file => (tenant, file.getName) }
            .toIndexedSeq
        }
      }
      .buffer(parallelism * 2, OverflowStrategy.backpressure)
      .mapAsync(parallelism) { case (tenant, name) => {
        (
          primaryStorage.getAccount(tenant.name, name)
          zip
          secondaryStorage.getAccount(tenant.name, name)
        )
        .flatMap {
          case (None, None) =>
            Future.successful(None)
          case (None, Some(b)) =>
            Future.successful(Some(b))
          case (Some(a), None) =>
            secondaryStorage
              .updateAccount(a)
              .map { _ =>
                markAsDirty()
                Some(a)
              }
          case (Some(a), Some(_)) =>
            Future.successful(Some(a))
        }
      } }
      .async
      .recover { case e: Exception =>
        logger.warn("Failed to get account caused by", e)
        None
      }
      .collect { case Some(data) => data }
      .buffer(parallelism * 2, OverflowStrategy.backpressure)
  }

  def getAccountSnapshotsFlow: Graph[FlowShape[PersistentAccount, Tuple2[PersistentAccount, PersistentAccountSnapshot]], NotUsed] = {
    Flow[PersistentAccount]
      .flatMapConcat { account =>
        Source {
          primaryStorage
            .getAccountSnapshotsPath(account.tenant, account.name)
            .toFile
            .listFiles()
            .map(_.getName.toInt)
            .filter(_ >= account.lastSynchronizedSnapshot)
            .sortWith(_ < _)
            .toIndexedSeq
        }
        .take(2)
        .map { version => (account, version) }
      }
      .buffer(1, OverflowStrategy.backpressure)
      .mapAsync(parallelism * 2) { case (account, version) => {
        primaryStorage
          .getAccountSnapshot(account.tenant, account.name, version)
          .map(_.map { snapshot => (account, snapshot) })
      } }
      .async
      .collect { case Some(data) => data }
      .buffer(1, OverflowStrategy.backpressure)
  }

  def getAccountEventsFlow: Graph[FlowShape[Tuple2[PersistentAccount, PersistentAccountSnapshot], Tuple3[PersistentAccount, PersistentAccountSnapshot, PersistentAccountEvent]], NotUsed] = {
    Flow[Tuple2[PersistentAccount, PersistentAccountSnapshot]]
      .map { case (account, snapshot) =>
        primaryStorage
          .getAccountEventsPath(account.tenant, account.name, snapshot.version)
          .toFile
          .listFiles()
          .map { file => (account, snapshot, file.getName) }
      }
      .buffer(parallelism * 4, OverflowStrategy.backpressure)
      .filterNot { events =>
        events.isEmpty ||
        (
          events.last._1.lastSynchronizedSnapshot == events.last._2.version &&
          events.last._1.lastSynchronizedEvent >= events.size
        )
      }
      .buffer(parallelism * 4, OverflowStrategy.backpressure)
      .mapAsync(parallelism * 4) { events =>
        Source(events.toIndexedSeq)
          .mapAsync(1000) { case (account, snapshot, event) =>
            primaryStorage
              .getAccountEvent(account.tenant, account.name, snapshot.version, event)
              .map(_.map { event => (account, snapshot, event) })
          }
          .runWith(Sink.seq)
          .map(_.flatten.sortWith(_._3.version < _._3.version))
      }
      .async
      .buffer(parallelism * 4, OverflowStrategy.backpressure)
      .mapConcat(_.to[Seq])
  }

  def getTransfersFlow: Graph[FlowShape[Tuple3[PersistentAccount, PersistentAccountSnapshot, PersistentAccountEvent], Tuple4[PersistentAccount, PersistentAccountSnapshot, PersistentAccountEvent, Seq[PersistentTransfer]]], NotUsed] = {
    Flow[Tuple3[PersistentAccount, PersistentAccountSnapshot, PersistentAccountEvent]]
      .flatMapConcat {
        case (account, snapshot, event) if event.status == 1 =>
          Source
            .fromPublisher(primaryStorage.getTransfers(account.tenant, event.transaction))
            .filter { transfer =>
              (transfer.creditTenant == account.tenant && transfer.creditAccount == account.name) ||
              (transfer.debitTenant == account.tenant && transfer.debitAccount == account.name)
            }
            .fold(Seq.empty[PersistentTransfer])(_ :+ _)
            .map { transfers => (account, snapshot, event, transfers) }

        case (account, snapshot, event) =>
          Source
            .single((account, snapshot, event, Seq.empty[PersistentTransfer]))
      }
      .buffer(parallelism, OverflowStrategy.backpressure)
      .mapAsync(parallelism) {

        case (account, snapshot, event, transfers) if transfers.isEmpty =>
          Future
            .successful {
              (account, snapshot, event, transfers)
            }

        case (account, snapshot, event, transfers) =>
          Future
            .sequence(transfers.map { transfer =>
              secondaryStorage
                .updateTransfer(transfer)
                .map(_ => transfer)
            })
            .map { transfers =>
              markAsDirty()
              (account, snapshot, event, transfers)
            }

      }
      .async
      .buffer(1, OverflowStrategy.backpressure)
      .mapAsync(1) {
        case (account, snapshot, event, transfers) => {
          markAsDirty()
          val nextAccount = account.copy(
            lastSynchronizedSnapshot = snapshot.version,
            lastSynchronizedEvent = event.version
          )
          secondaryStorage
            .updateAccount(nextAccount)
            .map { _ => (nextAccount, snapshot, event, transfers) }
        }
      }
      .async
  }

}
