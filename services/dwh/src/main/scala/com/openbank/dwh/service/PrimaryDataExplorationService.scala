package com.openbank.dwh.service

import java.nio.file.{Paths, Files, Path}
import akka.Done
import com.typesafe.scalalogging.LazyLogging
import scala.concurrent.{ExecutionContext, Future}
import scala.collection.mutable.Builder
import scala.collection.generic.CanBuildFrom
import language.higherKinds
import akka.stream.scaladsl.{Framing, FileIO, Flow, Keep, Sink, Source}
import akka.stream.{Materializer, OverflowStrategy}
import com.openbank.dwh.model.{Account, Tenant}
import com.openbank.dwh.persistence._
import collection.immutable.Seq


class PrimaryDataExplorationService(primaryStorage: PrimaryPersistence)(implicit ec: ExecutionContext, implicit val mat: Materializer) extends LazyLogging {

  sealed trait Discovery
  case class TenantDiscovery(name: String) extends Discovery
  case class AccountDiscovery(tenant: String, name: String) extends Discovery

  def runExploration: Future[Done] = {
    //.buffer(1, OverflowStrategy.backpressure)

    Source
      .single(primaryStorage.getRootPath())
      .via {
        Flow[Path]
          .map { file =>
            file
              .toFile
              .listFiles(_.getName.matches("t_.+"))
              .map(_.getName.stripPrefix("t_"))
              .map { name => TenantDiscovery(name) }
          }
          .mapConcat(_.to[collection.immutable.Seq])
          .mapAsync(1)(onTenantDiscovery)
          .recover { case e: Exception => None }
          .collect { case Some(tenant) => tenant }
      }
      .via {
        Flow[Tenant]
          .map { tenant =>
            (tenant, primaryStorage.getAccountsPath(tenant.name))
          }
      }
      .via {
        Flow[Tuple2[Tenant, Path]]
          .map { case (tenant, file) =>
            file
              .toFile
              .listFiles()
              .map(_.getName)
              .map { name => AccountDiscovery(tenant.name, name) }
          }
          .mapConcat(_.to[collection.immutable.Seq])
          .mapAsync(1)(onAccountDiscovery)
          .recover { case e: Exception => None }
          .collect { case Some(account) => account }
      }
      .runWith(Sink.seq)
      .map(_ => Done)

      // FIXME next step get events for each account
  }

  // FIXME rename to "acknowledge" something...
  private def onTenantDiscovery(item: TenantDiscovery): Future[Option[Tenant]] = {
    // FIXME now ask (call) secondary storage for Tenant entity, if it returns
    // None tell SecondaryDataPersistorActor about this tenant existence

    val result = primaryStorage.getTenant(item.name)

    // FIXME check if tenant has account and transaction subfolders

    result.map { data =>
      data.map { tenant =>
        logger.info(s"explored tenant ${item} as ${tenant}")
      }
      data
    }
  }

  // FIXME rename to "acknowledge" something...
  private def onAccountDiscovery(item: AccountDiscovery): Future[Option[Account]] = {

    // FIXME now ask (call) secondary storage for Account entity, if it returns
    // None get it from primary storage

    val result = primaryStorage.getAccountMetaData(item.tenant, item.name)

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

}
