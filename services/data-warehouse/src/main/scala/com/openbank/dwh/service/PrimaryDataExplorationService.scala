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
            Future.successful(tenant)
          case None =>
            val tenant = PersistentTenant(name)
            secondaryStorage
              .updateTenant(tenant)
              .map { _ =>
                logger.info("Discovered new Tenant {}", tenant.name)
                metrics.count("discovery.tenant", 1)
                tenant
              }
        }
      }
      .async
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
              Future.successful(account)
            case None =>
              primaryStorage
                .getAccount(tenant.name, name)
                .flatMap { account =>
                  secondaryStorage
                    .updateAccount(account)
                    .map { _ =>
                      logger.info(s"Discovered new Account {}/{}", account.tenant, account.name)
                      metrics.count("discovery.account", 1)
                      account
                    }
                }
          }
      }
      .async
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

        primaryStorage
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
      }
      .async
      .flatMapConcat(Source.apply)
      .mapAsync(1) { case (account, version) =>
        primaryStorage
          .getAccountSnapshot(account.tenant, account.name, version)
          .map { snapshot => (account, snapshot) }
      }
      .async
  }

  def getAccountEventsFlow: Flow[
    (PersistentAccount, PersistentAccountSnapshot),
    (PersistentAccount, PersistentAccountSnapshot, PersistentAccountEvent),
    NotUsed
  ] = {
    Flow[(PersistentAccount, PersistentAccountSnapshot)]
      .flatMapConcat { case (account, snapshot) =>
        val path = primaryStorage
          .getAccountEventsPath(
            account.tenant,
            account.name,
            snapshot.version
          )

        primaryStorage
          .listFiles(path)
          .map(_.getFileName.toString)
          .filterNot(_.isEmpty)
          .fold(Seq.empty[String])(_ :+ _)
          .filterNot { data =>
            data.isEmpty || (
              account.lastSynchronizedSnapshot == snapshot.version &&
                account.lastSynchronizedEvent >= data.size
            )
          }
          .flatMapConcat(Source.apply)
          .mapAsync(1) { event =>
            primaryStorage
              .getAccountEvent(
                account.tenant,
                account.name,
                snapshot.version,
                event
              )
          }
          .async
          .filterNot { event =>
            account.lastSynchronizedSnapshot == snapshot.version &&
            account.lastSynchronizedEvent > event.version
          }
          .fold(Seq.empty[PersistentAccountEvent])(_ :+ _)
          .map(_.sortBy(_.version))
          .flatMapConcat(Source.apply)
          .map { event => (account, snapshot, event) }
      }
      .async
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
            .async // FIXME backtrack check if both credit and debit accounts exists
            .mapAsync(1) { transfer =>
              secondaryStorage
                .getTransfer(
                  transfer.tenant,
                  transfer.transaction,
                  transfer.transfer
                )
                .flatMap {
                  case Some(transfer) =>
                    Future.successful(transfer)
                  case None =>
                    secondaryStorage
                      .updateTransfer(transfer)
                      .map { _ =>
                        logger.info(
                          "Discovered new Transfer {}/{}",
                          transfer.transaction,
                          transfer.transfer
                        )
                        metrics.count("discovery.transfer", 1)
                        transfer
                      }
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
)(implicit ec: ExecutionContext, mat: Materializer)
    extends StrictLogging {

  private val mutex = new Object()

  @volatile private var killSwitch: Option[UniqueKillSwitch] = None

  def killRunningWorkflow(): Future[Done] =
    mutex.synchronized {
      killSwitch.foreach(_.abort(new Exception("shutdown")))
      killSwitch = None
      Future.successful(Done)
    }

  def runExploration(): Future[Done] = {
    val worker = PrimaryDataExplorationWorker(primaryStorage, secondaryStorage, metrics)
    val (switch, result) = worker.runExploration()
    killSwitch = Some(switch)
    result
      .recoverWith { case e: Exception =>
        logger.error("Primary exploration failed", e)
        Future.successful(Done)
      }
  }

}
