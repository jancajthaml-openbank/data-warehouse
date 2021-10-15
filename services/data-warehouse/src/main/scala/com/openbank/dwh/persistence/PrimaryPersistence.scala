package com.openbank.dwh.persistence

import scala.collection.AbstractIterator
import com.typesafe.config.Config
import akka.util.ByteString
import java.nio.file.{Paths, Files, Path, DirectoryStream}
import com.openbank.dwh.model._
import akka.stream.Materializer
import scala.concurrent.Future
import akka.stream.scaladsl._
import scala.math.BigDecimal
import java.time.ZonedDateTime
import com.typesafe.scalalogging.StrictLogging
import scala.util.{Try, Success, Failure}
import akka.NotUsed

object PrimaryPersistence {

  def forConfig(config: Config): PrimaryPersistence = {
    new PrimaryPersistence(
      config.getString("data-exploration.primary.directory")
    )
  }

}

class DirectoryIterator(stream: DirectoryStream[Path])
    extends AbstractIterator[Path] {
  private lazy val it = stream.iterator()

  override def hasNext: Boolean =
    if (it.hasNext) {
      true
    } else {
      stream.close()
      false
    }

  override def next(): Path = it.next()
}

class PrimaryPersistence(val root: String) extends StrictLogging {

  def listFiles(path: Path): Source[Path, NotUsed] = {
    Try {
      new DirectoryIterator(Files.newDirectoryStream(path))
    } match {
      case Success(st) =>
        Source.fromIterator(() => st)
      case Failure(_) =>
        Source.empty
    }
  }

  def getRootPath(): Path =
    Paths.get(root)

  def getTenantPath(tenant: String): Path =
    Paths.get(s"$root/t_$tenant")

  def getAccountsPath(tenant: String): Path =
    Paths.get(s"$root/t_$tenant/account")

  def getTransactionsPath(tenant: String): Path =
    Paths.get(s"$root/t_$tenant/transaction")

  def getAccountSnapshotsPath(tenant: String, account: String): Path =
    Paths.get(f"$root/t_$tenant/account/$account/snapshot")

  def getAccountSnapshotPath(
      tenant: String,
      account: String,
      version: Int
  ): Path =
    Paths.get(
      f"$root/t_$tenant/account/$account/snapshot/$version%010d"
    )

  def getAccountEventsPath(
      tenant: String,
      account: String,
      version: Int
  ): Path =
    Paths.get(f"$root/t_$tenant/account/$account/events/$version%010d")

  def getAccountEventPath(
      tenant: String,
      account: String,
      version: Int,
      event: String
  ): Path =
    Paths.get(
      f"$root/t_$tenant/account/$account/events/$version%010d/$event"
    )

  def getTransactionPath(tenant: String, transaction: String): Path =
    Paths.get(f"$root/t_$tenant/transaction/$transaction")

  def getTenant(tenant: String): Future[PersistentTenant] = {
    if (Files.exists(getTenantPath(tenant))) {
      Future.successful(PersistentTenant(name = tenant))
    } else {
      Future.failed(
        new Exception(s"tenant $tenant does not exists in primary storage")
      )
    }
  }

  def getAccountSnapshot(
      tenant: String,
      account: String,
      version: Int
  ): Future[PersistentAccountSnapshot] = {
    if (Files.exists(getAccountSnapshotPath(tenant, account, version))) {
      Future.successful(PersistentAccountSnapshot(tenant, account, version))
    } else {
      Future.failed(
        new Exception(
          s"account snapshot $tenant/$account/$version does not exists in primary storage"
        )
      )
    }
  }

  def getAccountEvent(
      tenant: String,
      account: String,
      version: Int,
      event: String
  )(implicit mat: Materializer): Future[PersistentAccountEvent] = {
    Try {
      FileIO.fromPath(getAccountEventPath(tenant, account, version, event))
    } match {
      case Success(stream) =>
        stream
          .via(
            Framing
              .delimiter(
                ByteString(System.lineSeparator()),
                256,
                allowTruncation = true
              )
              .map(_.utf8String)
          )
          .take(1)
          .map { line =>
            val parts = event.split("_", 3)
            PersistentAccountEvent(
              tenant = tenant,
              account = account,
              status = parts(0).toShort,
              transaction = parts(2),
              snapshotVersion = version,
              version = line.toInt
            )
          }
          .runWith(Sink.last)
      case Failure(_) =>
        Future.failed(
          new Exception(
            s"account event $tenant/$account/$version/$event does not exists in primary storage"
          )
        )
    }
  }

  def getAccount(
      tenant: String,
      account: String
  )(implicit mat: Materializer): Future[PersistentAccount] = {
    Try {
      FileIO.fromPath(getAccountSnapshotPath(tenant, account, 0))
    } match {
      case Success(stream) =>
        stream
          .via(
            Framing
              .delimiter(
                ByteString(System.lineSeparator()),
                256,
                allowTruncation = true
              )
              .map(_.utf8String)
          )
          .take(1)
          .map { line =>
            PersistentAccount(
              tenant = tenant,
              name = account,
              currency = line.substring(0, 3),
              format = line.substring(4, line.length - 2),
              lastSynchronizedSnapshot = 0,
              lastSynchronizedEvent = 0
            )
          }
          .runWith(Sink.last)
      case Failure(_) =>
        Future.failed(
          new Exception(
            s"account $tenant/$account does not exists in primary storage"
          )
        )
    }
  }

  def getTransfers(
      tenant: String,
      transaction: String
  )(implicit mat: Materializer): Source[PersistentTransfer, NotUsed] = {
    Try {
      FileIO.fromPath(getTransactionPath(tenant, transaction))
    } match {
      case Success(stream) =>
        Source.fromPublisher {
          stream
            .via(
              Framing
                .delimiter(
                  ByteString(System.lineSeparator()),
                  256,
                  allowTruncation = true
                )
                .map(_.utf8String)
            )
            .statefulMapConcat { () =>
              var firstLine = true
              var status = 0

              {
                case line if firstLine =>
                  status = line match {
                    case "committed"  => 1
                    case "rollbacked" => 2
                    case _ =>
                      logger.warn(
                        "unknown transaction {}/{} status {}, falling back to promised",
                        tenant,
                        transaction,
                        line
                      )
                      0
                  }
                  firstLine = false
                  Nil

                case line =>
                  val parts = line.split(' ')
                  val transfer = PersistentTransfer(
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
                    valueDate = ZonedDateTime.parse(parts(5))
                  )

                  transfer :: Nil

              }
            }
            .runWith(Sink.asPublisher(fanout = false))
        }
      case Failure(_) =>
        logger.warn(
          "transaction {}/{} does not exists in primary storage",
          tenant,
          transaction
        )
        Source.empty
    }
  }

}
