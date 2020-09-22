package com.openbank.dwh.service

import java.nio.file.Path
import akka.{Done, NotUsed}
import com.typesafe.scalalogging.StrictLogging
import scala.concurrent.{ExecutionContext, Future}
import akka.stream.scaladsl._
import akka.stream._
import com.openbank.dwh.model._
import com.openbank.dwh.persistence._
import collection.immutable.Seq

class PrimaryDataExplorationService(
    primaryStorage: PrimaryPersistence,
    secondaryStorage: SecondaryPersistence
)(implicit ec: ExecutionContext, implicit val mat: Materializer)
    extends StrictLogging {

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
          case (_, Some(b)) =>
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
          ).flatMap {
            case (_, Some(b)) =>
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
          .flatMapConcat {
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
          .fold(Seq.empty[Tuple3[PersistentAccount, PersistentAccountSnapshot, String]])(_ :+ _)
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
                .filterNot { event =>
                  event._1.lastSynchronizedSnapshot == event._2.version &&
                  event._1.lastSynchronizedEvent > event._3.version
                }
                .sortWith(_._3.version < _._3.version)
                .toIndexedSeq
            }
      }
      .async
      .recover {
        case e: Exception =>
          logger.warn("Failed to get account events caused by", e)
          IndexedSeq[Tuple3[
            PersistentAccount,
            PersistentAccountSnapshot,
            PersistentAccountEvent
          ]]()
      }
      .flatMapConcat(Source.apply)
      .map {
        case (account, snapshot, event) =>
          val nextAccount = account.copy(
            lastSynchronizedSnapshot = snapshot.version,
            lastSynchronizedEvent = event.version
          )
          (nextAccount, snapshot, event)
      }
  }

  // FIXME improve flow
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
        case (account, snapshot, event) if event.status != 0 =>
          primaryStorage
            .getTransfers(account.tenant, event.transaction)
            .filter { transfer =>
              (transfer.creditTenant == account.tenant && transfer.creditAccount == account.name) ||
              (transfer.debitTenant == account.tenant && transfer.debitAccount == account.name)
            }
            .map {
              case transfer if transfer.status != event.status =>
                throw new Exception(s"Expected ${event.status} vs actual ${transfer.status} transfer status mismatch")
              case transfer =>
                transfer
            }
            .async
            .fold(Seq.empty[PersistentTransfer])(_ :+ _)
            .map { transfers => (account, snapshot, event, transfers) }

        case (account, snapshot, event) =>
          Source
            .single((account, snapshot, event, Seq.empty[PersistentTransfer]))
      }
      .flatMapConcat {

        case (account, snapshot, event, transfers) if transfers.isEmpty =>
          logger.debug(s"0 transfers in ${snapshot.version}/${event.version} for ${account}")
          Source.single((account, snapshot, event, transfers))

        case (account, snapshot, event, transfers) =>
          logger.debug(s"${transfers.size} transfers in ${snapshot.version}/${event.version} for ${account}")
          logger.info(s"Discovered new transaction ${transfers(0).transaction}")

          Source(transfers)
            .mapAsync(1) { transfer =>
              secondaryStorage
                .updateTransfer(transfer)
                .map(_ => transfer)
            }
            .async
            .fold(Seq.empty[PersistentTransfer])(_ :+ _)
            .map { transfers => (account, snapshot, event, transfers) }
      }
      .mapAsync(1) {
        case (account, snapshot, event, transfers) => {
          secondaryStorage
            .updateAccount(account)
            .map { _ => (account, snapshot, event, transfers) }
        }
      }
      .async
  }

}
