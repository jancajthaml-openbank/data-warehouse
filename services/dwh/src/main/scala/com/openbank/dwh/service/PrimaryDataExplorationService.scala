package com.openbank.dwh.service

import java.nio.file.{Paths, Files, Path}
import akka.Done
import akka.util.ByteString
import com.typesafe.scalalogging.LazyLogging
import scala.concurrent.{ExecutionContext, Future}
import scala.collection.mutable.Builder
import scala.collection.generic.CanBuildFrom
import language.higherKinds
import akka.stream.scaladsl.{Framing, FileIO, Flow, Keep, Sink, Source}
import akka.stream.{Materializer, OverflowStrategy}
import com.openbank.dwh.model.{Account, Tenant}


class PrimaryDataExplorationService(primaryStorage: String)(implicit ec: ExecutionContext, implicit val mat: Materializer) extends LazyLogging {

  sealed trait Discovery
  case class TenantDiscovery(name: String) extends Discovery
  case class AccountDiscovery(tenant: String, name: String) extends Discovery

  def runExploration: Future[Done] = {
    //.buffer(1, OverflowStrategy.backpressure)

    Source
      .fromFuture(exploreTenants())
      .mapConcat(_.to[collection.immutable.Seq])
      .foldAsync(Seq.empty[Account])((current, item) => {
        exploreAccountNames(item.name).map(_ ++ current)
      })
      .runWith(Sink.seq)
      .map(_ => Done)
  }

  // FIXME convert to graph stage and not future
  def exploreTenants(): Future[Seq[Tenant]] = {
    Source
      .single(Paths.get(primaryStorage))
      .via {
        Flow[Path]
          .map { file =>
            file
              .toFile
              .listFiles(_.getName.matches("t_.+"))
              .map(_.getName.stripPrefix("t_"))
              .map { tenant => TenantDiscovery(tenant) }
          }
          .fold(List.empty[TenantDiscovery])(_ ++ _)
      }
      .mapConcat(_.to[collection.immutable.Seq])
      .via {
        Flow[TenantDiscovery]
          .mapAsync(1)(onTenantDiscovery)
      }
      .recover {
        case e: Exception => None
      }
      .collect { case Some(tenant) => tenant }
      .runWith(Sink.seq)
  }

  // FIXME convert to graph stage and not future
  def exploreAccountNames(tenant: String): Future[Seq[Account]] = {
    Source
      .single(Paths.get(s"${primaryStorage}/t_${tenant}/account"))
      .via {
        Flow[Path]
          .map { file =>
            file
              .toFile
              .listFiles()
              .map(_.getName)
              .map { name => AccountDiscovery(tenant, name) }
          }
          .fold(List.empty[AccountDiscovery])(_ ++ _)
      }
      .mapConcat(_.to[collection.immutable.Seq])
      .via {
        Flow[AccountDiscovery]
          .mapAsync(1)(onAccountDiscovery)
      }
      .recover {
        case e: Exception => None
      }
      .collect { case Some(account) => account }
      .runWith(Sink.seq)
  }

  // FIXME should not return Done but Tenant instead
  // FIXME rename to "acknowledge" something...
  private def onTenantDiscovery(item: TenantDiscovery): Future[Option[Tenant]] = {
    // FIXME now ask (call) secondary storage for Tenant entity, if it returns
    // None tell SecondaryDataPersistorActor about this tenant existence

    val result = Some(Tenant(item.name))

    result.map { data =>
      logger.info(s"explored tenant ${item} as ${result}")
    }

    Future.successful(result)
  }

  // FIXME should not return Done but Account instead
  // FIXME rename to "acknowledge" something...
  private def onAccountDiscovery(item: AccountDiscovery): Future[Option[Account]] = {

    // FIXME now ask (call) secondary storage for Account entity, if it returns
    // None get it from primary storage

    val result = getAccountFromPrimaryStorage(item.tenant, item.name)

    // FIXME if whatever we end up with is different than what we obtained from
    // primary storage (if necessary) tell SecondaryDataPersistorActor about this
    // account existence

    result.map { data =>
      data.map { account =>
        logger.info(s"explored account name ${item} as ${account}")
      }
      data
    }
  }

  // FIXME move to primary storage peristence
  private def getAccountFromPrimaryStorage(tenant: String, name: String): Future[Option[Account]] = {
    val file = Paths.get(s"${primaryStorage}/t_${tenant}/account/${name}/snapshot/0000000000")
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
