package com.openbank.dwh.service

import com.openbank.dwh.metrics.StatsDClient
import java.nio.file.Path
import akka.{Done, NotUsed}
import com.typesafe.scalalogging.StrictLogging
import scala.concurrent.{ExecutionContext, Future}
import akka.stream.scaladsl._
import akka.stream._
import com.openbank.dwh.model._
import com.openbank.dwh.persistence._
import collection.immutable.Seq

case class PrimaryDataExplorationWorker(
    primaryStorage: PrimaryPersistence,
    secondaryStorage: SecondaryPersistence,
    metrics: StatsDClient
)(implicit ec: ExecutionContext, mat: Materializer)
    extends StrictLogging {

  def runExploration(): (UniqueKillSwitch, Future[Done]) = {
    Source
      .single(primaryStorage.getRootPath())
      .via(getTenantsFlow)
      .via(getAccountsFlow)
      .via(getAccountSnapshotsFlow)
      .via(getAccountEventsFlow)
      .via(getTransfersFlow)
      .viaMat(KillSwitches.single)(Keep.right)
      .toMat(Sink.ignore)(Keep.both)
      .run()
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
        secondaryStorage.getTenant(name).flatMap {
          case Some(tenant) =>
            Future.successful(Some(tenant))
          case None =>
            val tenant = PersistentTenant(name)
            logger.info("Discovered new Tenant {}", tenant.name)
            metrics.count("discovery.tenant", 1)
            secondaryStorage
              .updateTenant(tenant)
              .map { _ => Some(tenant) }
        }
      }
      .async
      .recover { case e: Exception =>
        logger.warn("Failed to get tenant caused by", e)
        None
      }
      .collect { case Some(tenant) => tenant }
  }

  def getAccountsFlow: Graph[FlowShape[PersistentTenant, PersistentAccount], NotUsed] = {
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
      .mapAsync(1) { case (tenant, name) =>
        secondaryStorage
          .getAccount(tenant.name, name)
          .flatMap {
            case Some(account) =>
              Future.successful(Some(account))
            case None =>
              primaryStorage
                .getAccount(tenant.name, name)
                .flatMap { account =>
                  logger.info(s"Discovered new Account {}/{}", account.tenant, account.name)
                  metrics.count("discovery.account", 1)
                  secondaryStorage
                    .updateAccount(account)
                    .map { _ => Some(account) }
                }
          }
      }
      .async
      .recover { case e: Exception =>
        logger.warn("Failed to get account caused by", e)
        None
      }
      .collect { case Some(data) => data }
  }

  protected def getAccountSnapshotsFlow: Flow[
    PersistentAccount,
    (PersistentAccount, PersistentAccountSnapshot),
    NotUsed
  ] = {
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
      .mapAsync(1) { case (account, version) =>
        primaryStorage
          .getAccountSnapshot(account.tenant, account.name, version)
          .map { snapshot => Some((account, snapshot)) }
      }
      .async
      .recover { case e: Exception =>
        logger.warn("Failed to get account snapshot caused by", e)
        None
      }
      .collect { case Some(data) => data }
  }

  private def getNewAccountEvents(
      account: PersistentAccount,
      snapshot: PersistentAccountSnapshot
  ) =
    Source
      .single((account, snapshot))
      .flatMapConcat { case (account, snapshot) =>
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
      .fold(
        Seq.empty[(PersistentAccount, PersistentAccountSnapshot, String)]
      )(_ :+ _)
      .filterNot(_.isEmpty)
      .filterNot { data =>
        data.last._1.lastSynchronizedSnapshot == data.last._2.version &&
        data.last._1.lastSynchronizedEvent >= data.size
      }
      .flatMapConcat(Source.apply)
      .mapAsync(1) { case (account, snapshot, event) =>
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
      .filterNot { event =>
        event._1.lastSynchronizedSnapshot == event._2.version &&
        event._1.lastSynchronizedEvent > event._3.version
      }
      .runWith(Sink.seq)
      .map { events =>
        events.sortWith(_._3.version < _._3.version)
      }

  def getAccountEventsFlow: Flow[
    (PersistentAccount, PersistentAccountSnapshot),
    (PersistentAccount, PersistentAccountSnapshot, PersistentAccountEvent),
    NotUsed
  ] = {

    Flow[(PersistentAccount, PersistentAccountSnapshot)]
      .mapAsync(1) { case (account, snapshot) =>
        getNewAccountEvents(account, snapshot)
      }
      .async
      .recover { case e: Exception =>
        logger.warn("Failed to get account events caused by", e)
        IndexedSeq[
          (
              PersistentAccount,
              PersistentAccountSnapshot,
              PersistentAccountEvent
          )
        ]()
      }
      .flatMapConcat(Source.apply)
      .map { case (account, snapshot, event) =>
        val nextAccount = account.copy(
          lastSynchronizedSnapshot = snapshot.version,
          lastSynchronizedEvent = event.version
        )
        (nextAccount, snapshot, event)
      }
  }

  def getTransfersFlow: Graph[FlowShape[
    (
        PersistentAccount,
        PersistentAccountSnapshot,
        PersistentAccountEvent
    ),
    (
        PersistentAccount,
        PersistentAccountSnapshot,
        PersistentAccountEvent,
        Seq[PersistentTransfer]
    )
  ], NotUsed] = {
    Flow[
      (
          PersistentAccount,
          PersistentAccountSnapshot,
          PersistentAccountEvent
      )
    ]
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
                throw new Exception(
                  s"Expected ${event.status} vs actual ${transfer.status} transfer status mismatch"
                )
              case transfer =>
                transfer
            }
            .async
            .mapAsync(1) { transfer =>
              secondaryStorage
                .getTransfer(
                  transfer.tenant,
                  transfer.transaction,
                  transfer.transfer
                )
                .flatMap {
                  case Some(b) =>
                    Future.successful(b)
                  case None =>
                    logger.info(
                      "Discovered new Transfer {}/{}",
                      transfer.transaction,
                      transfer.transfer
                    )
                    metrics.count("discovery.transfer", 1)

                    secondaryStorage
                      .updateTransfer(transfer)
                      .map { _ => transfer }
                }
            }
            .async
            .fold(Seq.empty[PersistentTransfer])(_ :+ _)
            .map { transfers => (account, snapshot, event, transfers) }

        case (account, snapshot, event) =>
          Source
            .single((account, snapshot, event, Seq.empty[PersistentTransfer]))
      }
      .mapAsync(1) { case (account, snapshot, event, transfers) =>
        secondaryStorage
          .updateAccount(account)
          .map { _ => (account, snapshot, event, transfers) }
      }
      .async
  }

}

class PrimaryDataExplorationService(
    primaryStorage: PrimaryPersistence,
    secondaryStorage: SecondaryPersistence,
    metrics: StatsDClient
) extends StrictLogging {

  private val mutex = new Object()
  @volatile private var killSwitch: Option[UniqueKillSwitch] = None

  def killRunningWorkflow(): Future[Done] =
    mutex.synchronized {
      killSwitch.foreach(_.abort(new Exception("shutdown")))
      killSwitch = None
      Future.successful(Done)
    }

  def runExploration(ec: ExecutionContext, mat: Materializer): Future[Done] = {
    val worker =
      PrimaryDataExplorationWorker(primaryStorage, secondaryStorage, metrics)(
        ec,
        mat
      )
    val (switch, result) = worker.runExploration()
    killSwitch = Some(switch)
    result
      .recoverWith { case e: Exception =>
        logger.error("Primary exploration failed", e)
        Future.successful(Done)
      }(ec)
  }

}
