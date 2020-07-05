package com.openbank.dwh.persistence

import com.typesafe.config.Config
import akka.util.ByteString
import java.nio.file.{Paths, Files, Path}
import com.openbank.dwh.model._
import akka.stream.Materializer
import scala.concurrent.{ExecutionContext, Future}
import akka.stream.scaladsl.{Framing, FileIO, Flow, Keep, Sink, Source}
import com.typesafe.scalalogging.LazyLogging
import collection.immutable.Seq


class PrimaryPersistence(val rootStorage: String)(implicit ec: ExecutionContext, implicit val mat: Materializer) extends LazyLogging {

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

  def getTenant(tenant: String): Future[Option[Tenant]] = {
    if (!Files.exists(getTenantPath(tenant))) {
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
      return Future.successful(None)
    }
    return Future.successful(Some(AccountSnapshot(tenant, account, version)))
  }

  def getAccountEvent(tenant: String, account: String, version: Int, event: String): Future[Option[AccountEvent]] = {
    val file = getAccountEventPath(tenant, account, version, event)
    if (!Files.exists(file)) {
      return Future.successful(None)
    }

    FileIO.fromPath(file)
      .via(Framing.delimiter(ByteString("\n"), 256, true).map(_.utf8String))
      .take(1)
      .map { line =>
        val parts = event.split("_")
        Some(AccountEvent(tenant, account, parts(0).toInt, parts(2), version, line.toInt))
      }
      .recover {
        case e: Exception =>
          logger.warn(s"error reading account event data of tenant: ${tenant} account: ${account} exception snapshot: ${version} event: ${event}: ${e}")
          None
      }
      .runWith(Sink.reduce[Option[AccountEvent]]((_, last) => last))
  }

  def getAccount(tenant: String, account: String): Future[Option[Account]] = {
    val file = getAccountSnapshotPath(tenant, account, 0)
    if (!Files.exists(file)) {
      return Future.successful(None)
    }

    FileIO.fromPath(file)
      .via(Framing.delimiter(ByteString("\n"), 256, true).map(_.utf8String))
      .take(1)
      .map { line =>
        Some(Account(tenant, account, line.substring(0, 3), line.substring(4, line.size - 2), 0, 0, false))
      }
      .recover {
        case e: Exception =>
          logger.warn(s"error reading account meta data of tenant: ${tenant} account: ${account} exception: ${e}")
          None
      }
      .runWith(Sink.reduce[Option[Account]]((_, last) => last))
  }

}
