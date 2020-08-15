package com.openbank.dwh.service

import java.nio.file.{Paths, Files, Path}
import akka.{Done, NotUsed}
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

class PrimaryDataExplorationService(
    primaryStorage: PrimaryPersistence,
    secondaryStorage: SecondaryPersistence
)(implicit ec: ExecutionContext, implicit val mat: Materializer)
    extends StrictLogging {

  // on topic of streams
  // http://beyondthelines.net/computing/akka-streams-patterns/

  @volatile private var killSwitch: Option[UniqueKillSwitch] = None

  def killRunningWorkflow(): Future[Done] =
    this.synchronized {
      killSwitch.foreach(_.abort(new Exception("shutdown")))
      killSwitch = None
      Future.successful(Done)
    }

  def exploreAccounts(): Future[Done] = {
    val (switch, result) = Source
      .single(primaryStorage.getRootPath())
      .via(getTenantsFlow)
      .via(getAccountsFlow)
      .viaMat(KillSwitches.single)(Keep.right)
      .toMat(Sink.ignore)(Keep.both)
      .run()

    killSwitch = Some(switch)
    result
  }

  def exploreTransfers(): Future[Done] = {
    val (switch, result) = Source
      .single(primaryStorage.getRootPath())
      .via(getTenantsFlow)
      .via(getAccountsFlow)
      .via(getAccountSnapshotsFlow)
      .via(getAccountEventsFlow)
      .via(getTransfersFlow)
      .viaMat(KillSwitches.single)(Keep.right)
      .toMat(Sink.ignore)(Keep.both)
      .run()

    killSwitch = Some(switch)
    result
  }

  def getTenantsFlow: Graph[FlowShape[Path, PersistentTenant], NotUsed] = {
    Flow[Path]
      .flatMapConcat { path =>
        primaryStorage
          .listFiles(path)
          .map(_.getFileName.toString)
          .filterNot(_.isEmpty)
          .filter(_.matches("t_.+"))
          .map(_.stripPrefix("t_"))
      }
      .mapAsync(1) { name =>
        (
          primaryStorage.getTenant(name)
            zip
              secondaryStorage.getTenant(name)
        ).flatMap {
          case (a, Some(b)) =>
            Future.successful(Some(b))
          case (a, None) =>
            logger.info(s"Discovered new Tenant ${a}")
            secondaryStorage
              .updateTenant(a)
              .map { _ => Some(a) }
        }
      }
      .async
      .recover {
        case e: Exception =>
          logger.warn("Failed to get tenant caused by", e)
          None
      }
      .collect { case Some(tenant) => tenant }
  }

  def getAccountsFlow
      : Graph[FlowShape[PersistentTenant, PersistentAccount], NotUsed] = {
    Flow[PersistentTenant]
      .flatMapConcat { tenant =>
        val path = primaryStorage
          .getAccountsPath(tenant.name)

        primaryStorage
          .listFiles(path)
          .map(_.getFileName.toString)
          .filterNot(_.isEmpty)
          .map { file => (tenant, file) }
      }
      .mapAsync(1) {
        case (tenant, name) => {
          (
            primaryStorage.getAccount(tenant.name, name)
              zip
                secondaryStorage.getAccount(tenant.name, name)
          )
            .flatMap {
              case (a, Some(b)) =>
                Future.successful(Some(b))
              case (a, None) =>
                logger.info(s"Discovered new Account ${a}")
                secondaryStorage
                  .updateAccount(a)
                  .map { _ => Some(a) }
            }
        }
      }
      .async
      .recover {
        case e: Exception =>
          logger.warn("Failed to get account caused by", e)
          None
      }
      .collect { case Some(data) => data }
  }

  def getAccountSnapshotsFlow: Graph[FlowShape[
    PersistentAccount,
    Tuple2[PersistentAccount, PersistentAccountSnapshot]
  ], NotUsed] = {
    Flow[PersistentAccount]
      .mapAsync(1) { account =>
        val path = primaryStorage
          .getAccountSnapshotsPath(account.tenant, account.name)

        val snapshots = primaryStorage
          .listFiles(path)
          .map(_.getFileName.toString)
          .filterNot(_.isEmpty)
          .map(_.toInt)
          .filter(_ >= account.lastSynchronizedSnapshot)
          .runWith(Sink.seq)
          .map { result =>
            result
              .sortWith(_ < _)
              .take(2)
              .map { version => (account, version) }
              .toIndexedSeq
          }

        snapshots
      }
      .async
      .flatMapConcat(Source.apply)
      .mapAsync(1) {
        case (account, version) => {
          primaryStorage
            .getAccountSnapshot(account.tenant, account.name, version)
            .map { snapshot => (account, snapshot) }
            .map { data => Some(data) }
        }
      }
      .async
      .recover {
        case e: Exception =>
          logger.warn("Failed to get account snapshot caused by", e)
          None
      }
      .collect { case Some(data) => data }
  }

  def getAccountEventsFlow: Graph[FlowShape[
    Tuple2[PersistentAccount, PersistentAccountSnapshot],
    Tuple3[PersistentAccount, PersistentAccountSnapshot, PersistentAccountEvent]
  ], NotUsed] = {

    val substream =
      (account: PersistentAccount, snapshot: PersistentAccountSnapshot) =>
        Source
          .single((account, snapshot))
          .map {
            case (account, snapshot) =>
              val path = primaryStorage
                .getAccountEventsPath(
                  account.tenant,
                  account.name,
                  snapshot.version
                )

              val events = primaryStorage
                .listFiles(path)
                .map(_.getFileName.toString)
                .filterNot(_.isEmpty)
                .map { file => (account, snapshot, file) }

              events
          }
          .flatMapConcat { eventsStream =>
            Source.future(eventsStream.runWith(Sink.seq))
          }
          .filterNot(_.isEmpty)
          .filterNot { data =>
            data.last._1.lastSynchronizedSnapshot == data.last._2.version &&
            data.last._1.lastSynchronizedEvent >= data.size
          }
          .flatMapConcat(Source.apply)
          .mapAsync(1) {
            case (account, snapshot, event) =>
              primaryStorage
                .getAccountEvent(
                  account.tenant,
                  account.name,
                  snapshot.version,
                  event
                )
                .map { event => (account, snapshot, event) }
          }
          .async
          .runWith(Sink.seq)

    Flow[Tuple2[PersistentAccount, PersistentAccountSnapshot]]
      .mapAsync(1) {
        case (account, snapshot) =>
          substream(account, snapshot)
            .map { result =>
              result
                .sortWith(_._3.version < _._3.version)
                .toIndexedSeq
            }
      }
      .async
      .recover {
        case e: Exception =>
          logger.warn("Failed to get account events caused by", e)
          Array
            .empty[Tuple3[
              PersistentAccount,
              PersistentAccountSnapshot,
              PersistentAccountEvent
            ]]
            .toIndexedSeq
      }
      .flatMapConcat(Source.apply)
  }

  def getTransfersFlow: Graph[FlowShape[Tuple3[
    PersistentAccount,
    PersistentAccountSnapshot,
    PersistentAccountEvent
  ], Tuple4[
    PersistentAccount,
    PersistentAccountSnapshot,
    PersistentAccountEvent,
    Seq[PersistentTransfer]
  ]], NotUsed] = {
    Flow[Tuple3[
      PersistentAccount,
      PersistentAccountSnapshot,
      PersistentAccountEvent
    ]]
      .flatMapConcat {
        case (account, snapshot, event) if event.status == 1 =>
          primaryStorage
            .getTransfers(account.tenant, event.transaction)
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
      .mapAsync(1) {

        case (account, snapshot, event, transfers) if transfers.isEmpty =>
          Future
            .successful {
              (account, snapshot, event, transfers)
            }

        case (account, snapshot, event, transfers) =>
          logger.info(s"Discovered new Transaction ${transfers(0).transaction}")

          Future
            .fold {
              transfers
                .map { transfer =>
                  secondaryStorage
                    .updateTransfer(transfer)
                    .map(_ => transfer)
                }
            }(Seq.empty[PersistentTransfer])(_ :+ _)
            .map { transfers =>
              (account, snapshot, event, transfers)
            }
      }
      .async
      .mapAsync(1) {
        case (account, snapshot, event, transfers) => {
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
