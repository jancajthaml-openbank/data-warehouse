package com.openbank.dwh.persistence

import com.typesafe.config.Config
import akka.util.ByteString
import java.nio.file.{Paths, Files, Path}
import com.openbank.dwh.model._
import akka.stream.Materializer
import scala.concurrent.{ExecutionContext, Future}
import akka.stream.scaladsl._
import collection.immutable.Seq
import scala.math.BigDecimal
import java.time.ZonedDateTime
import com.typesafe.scalalogging.LazyLogging
import org.reactivestreams.Publisher


object PrimaryPersistence {

  def forConfig(config: Config, ec: ExecutionContext, mat: Materializer): PrimaryPersistence = {
    new PrimaryPersistence(config.getString("persistence-primary.directory"))(ec, mat)
  }

}


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

  def getTenant(tenant: String): Future[Option[PersistentTenant]] = {
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
    return Future.successful(
      Some(PersistentTenant(
        name = tenant
      ))
    )
  }

  def getAccountSnapshot(tenant: String, account: String, version: Int): Future[Option[PersistentAccountSnapshot]] = {
    if (!Files.exists(getAccountSnapshotPath(tenant, account, version))) {
      logger.warn(s"account snapshot ${tenant}/${account}/${version} does not exists in primary storage")
      return Future.successful(None)
    }
    return Future.successful(Some(PersistentAccountSnapshot(tenant, account, version)))
  }

  def getAccountEvent(tenant: String, account: String, version: Int, event: String): Future[Option[PersistentAccountEvent]] = {
    // FIXME instead of future combine sources (first nil then something) and take last
    val file = getAccountEventPath(tenant, account, version, event)
    if (!Files.exists(file)) {
      logger.warn(s"account event ${tenant}/${account}/${version}/${event} does not exists in primary storage")
      return Future.successful(None)
    }

    FileIO.fromPath(file)
      .via(Framing.delimiter(ByteString(System.lineSeparator()), 256, true).map(_.utf8String))
      .take(1)
      .map { line =>
        val parts = event.split("_", 3)
        Some(PersistentAccountEvent(
          tenant = tenant,
          account = account,
          status = parts(0).toShort,
          transaction = parts(2),
          snapshotVersion = version,
          version = line.toInt
        ))
      }
      .runWith(Sink.reduce[Option[PersistentAccountEvent]]((_, last) => last))
  }

  def getAccount(tenant: String, account: String): Future[Option[PersistentAccount]] = {
    // FIXME instead of future combine sources (first nil then something) and take last
    val file = getAccountSnapshotPath(tenant, account, 0)
    if (!Files.exists(file)) {
      logger.warn(s"account ${tenant}/${account} does not exists in primary storage")
      return Future.successful(None)
    }

    FileIO.fromPath(file)
      .via(Framing.delimiter(ByteString(System.lineSeparator()), 256, true).map(_.utf8String))
      .take(1)
      .map { line =>
        Some(PersistentAccount(
          tenant = tenant,
          name = account,
          currency = line.substring(0, 3),
          format = line.substring(4, line.size - 2),
          lastSynchronizedSnapshot = 0,
          lastSynchronizedEvent = 0
        ))
      }
      .runWith(Sink.reduce[Option[PersistentAccount]]((_, last) => last))
  }

  def getTransfers(tenant: String, transaction: String): Publisher[PersistentTransfer] = {
    val file = getTransactionPath(tenant, transaction)
    if (!Files.exists(file)) {
      logger.warn(s"transaction ${tenant}/${transaction} does not exists in primary storage")
      return Source.empty.runWith(Sink.asPublisher(fanout = false))
    }

    FileIO.fromPath(file)
      .via(Framing.delimiter(ByteString(System.lineSeparator()), 256, true).map(_.utf8String))
      .drop(1)
      .map { line =>
        val parts = line.split(' ')
        PersistentTransfer(
          tenant = tenant,
          transaction = transaction,
          transfer = parts(0),
          creditTenant = parts(1),
          creditAccount = parts(2),
          debitTenant = parts(3),
          debitAccount = parts(4),
          amount = BigDecimal.exact(parts(6)),
          currency = parts(7),
          valueDate = ZonedDateTime.parse(parts(5)),
          isPristine = false
        )
      }
      .runWith(Sink.asPublisher(fanout = false))
  }

}
