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

  def getAccountSnapshotPath(tenant: String, name: String, version: Int): Path =
    Paths.get(f"${rootStorage}/t_${tenant}/account/${name}/snapshot/${version}%010d")

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
    return Future.successful(Some(Tenant(tenant)))
  }

  def getAccountMetaData(tenant: String, name: String): Future[Option[Account]] = {
    val file = getAccountSnapshotPath(tenant, name, 0)
    if (!Files.exists(file)) {
      return Future.successful(None)
    }

    FileIO.fromPath(file)
      .via(Framing.delimiter(ByteString("\n"), 256, true).map(_.utf8String))
      .take(1)
      .map { line =>
        Some(Account(tenant, name, line.substring(0, 3), line.substring(4, line.size - 2)))
      }
      .recover {
        case e: Exception =>
          logger.warn(s"error reading account meta data of tenant: ${tenant} name: ${name} exception: ${e}")
          None
      }
      .runWith(Sink.reduce[Option[Account]]((_, last) => last))
  }

}
