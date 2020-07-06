package com.openbank.dwh.persistence

import com.typesafe.config.Config
import akka.util.ByteString
import java.nio.file.{Paths, Files, Path}
import com.openbank.dwh.model._
import akka.stream.Materializer
import scala.concurrent.{ExecutionContext, Future}
import akka.stream.scaladsl.{Framing, FileIO, Flow, Keep, Sink, Source}
import collection.immutable.Seq
import scala.math.BigDecimal
import com.typesafe.scalalogging.LazyLogging


// FIXME split into interface and impl for better testing
class PrimaryPersistence(val rootStorage: String)(implicit ec: ExecutionContext, implicit val mat: Materializer) extends LazyLogging {

  def getLastModificationTime(): Long =
    Paths.get(rootStorage).toFile.lastModified()

  def getRootPath(): Path =
    Paths.get(rootStorage)

  def getTenantPath(tenant: String): Path =
    Paths.get(s"${rootStorage}/t_${tenant}")

  def getAccountsPath(tenant: String): Path =
    Paths.get(s"${rootStorage}/t_${tenant}/account")

  def getTransactionsPath(tenant: String): Path =
    Paths.get(s"${rootStorage}/t_${tenant}/transaction")

  def getAccountSnapshotsPath(tenant: String, account: String): Path =
    Paths.get(f"${rootStorage}/t_${tenant}/account/${account}/snapshot")

  def getAccountSnapshotPath(tenant: String, account: String, version: Int): Path =
    Paths.get(f"${rootStorage}/t_${tenant}/account/${account}/snapshot/${version}%010d")

  def getAccountEventsPath(tenant: String, account: String, version: Int): Path =
    Paths.get(f"${rootStorage}/t_${tenant}/account/${account}/events/${version}%010d")

  def getAccountEventPath(tenant: String, account: String, version: Int, event: String): Path =
    Paths.get(f"${rootStorage}/t_${tenant}/account/${account}/events/${version}%010d/${event}")

  def getTransactionPath(tenant: String, transaction: String): Path =
    Paths.get(f"${rootStorage}/t_${tenant}/transaction/${transaction}")

  def getTenant(tenant: String): Future[Option[Tenant]] = {
    if (!Files.exists(getTenantPath(tenant))) {
      logger.warn(s"tenant ${tenant} does not exists in primary storage")
      return Future.successful(None)
    }
    if (!Files.exists(getTransactionsPath(tenant))) {
      return Future.successful(None)
    }
    if (!Files.exists(getAccountsPath(tenant))) {
      return Future.successful(None)
    }
    return Future.successful(Some(Tenant(tenant, false)))
  }

  def getAccountSnapshot(tenant: String, account: String, version: Int): Future[Option[AccountSnapshot]] = {
    if (!Files.exists(getAccountSnapshotPath(tenant, account, version))) {
      logger.warn(s"account snapshot ${tenant}/${account}/${version} does not exists in primary storage")
      return Future.successful(None)
    }
    return Future.successful(Some(AccountSnapshot(tenant, account, version)))
  }

  def getAccountEvent(tenant: String, account: String, version: Int, event: String): Future[Option[AccountEvent]] = {
    val file = getAccountEventPath(tenant, account, version, event)
    if (!Files.exists(file)) {
      logger.warn(s"account event ${tenant}/${account}/${version}/${event} does not exists in primary storage")
      return Future.successful(None)
    }

    FileIO.fromPath(file)
      .via(Framing.delimiter(ByteString("\n"), 256, true).map(_.utf8String))
      .take(1)
      .map { line =>
        val parts = event.split("_", 3)
        Some(AccountEvent(tenant, account, parts(0).toInt, parts(2), version, line.toInt))
      }
      .runWith(Sink.reduce[Option[AccountEvent]]((_, last) => last))
  }

  def getAccount(tenant: String, account: String): Future[Option[Account]] = {
    val file = getAccountSnapshotPath(tenant, account, 0)
    if (!Files.exists(file)) {
      logger.warn(s"account ${tenant}/${account} does not exists in primary storage")
      return Future.successful(None)
    }

    FileIO.fromPath(file)
      .via(Framing.delimiter(ByteString("\n"), 256, true).map(_.utf8String))
      .take(1)
      .map { line =>
        Some(Account(
          tenant = tenant,
          name = account,
          currency = line.substring(0, 3),
          format = line.substring(4, line.size - 2),
          lastSynchronizedSnapshot = 0,
          lastSynchronizedEvent = 0,
          isPristine = false
        ))
      }
      .runWith(Sink.reduce[Option[Account]]((_, last) => last))
  }

  def getTransfers(tenant: String, transaction: String): Future[Seq[Transfer]] = {
    val file = getTransactionPath(tenant, transaction)
    if (!Files.exists(file)) {
      logger.warn(s"transaction ${tenant}/${transaction} does not exists in primary storage")
      return Future.successful(Seq.empty[Transfer])
    }

    FileIO.fromPath(file)
      .via(Framing.delimiter(ByteString("\n"), 256, true).map(_.utf8String))
      .via {
        Flow[String]
          .statefulMapConcat { () =>
            var status: String = ""
            line: String => {
              if (status == "") {
                status = line
                Nil
              } else {
                val parts = line.split(' ')
                Transfer(
                  tenant = tenant,
                  transaction = transaction,
                  transfer = parts(0),
                  status = status,
                  creditTenant = parts(1),
                  creditAccount = parts(2),
                  debitTenant = parts(3),
                  debitAccount = parts(4),
                  amount = BigDecimal.exact(parts(6)),
                  currency = parts(7),
                  valueDate = parts(5),
                  isPristine=false
                ) :: Nil
              }
            }
        }
      }
      .runWith(Sink.seq)
  }

}
