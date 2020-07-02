package com.openbank.dwh.service

import java.nio.file.{Paths, Path}
import akka.Done
import com.typesafe.scalalogging.LazyLogging
import scala.concurrent.{ExecutionContext, Future}
import scala.collection.mutable.Builder
import scala.collection.generic.CanBuildFrom
import language.higherKinds
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import akka.stream.{Materializer, OverflowStrategy}

// https://doc.akka.io/docs/akka/current/stream/stream-dynamic.html
// https://algd.github.io/akka/2018/08/05/parallel-stream-processing.html

class PrimaryDataExplorationService(primaryStorage: String)(implicit ec: ExecutionContext, implicit val mat: Materializer) extends LazyLogging {

  sealed trait Discovery
  case class TenantDiscovery(name: String) extends Discovery
  case class AccountDiscovery(tenant: String, name: String) extends Discovery

  def runExploration: Future[Done] = {
    Source
      .fromFuture(exploreTenants())
      .foldAsync(Seq.empty[AccountDiscovery])((current, item) => {
        exploreAccountNames(item.name).map(_ ++ current)
      })
      .runWith(Sink.seq)
      .map(_ => Done)

      //.mapConcat(_.to[collection.immutable.Seq])  // FIXME try delete casting
  }

  // FIXME convert to graph stage and not future
  def exploreTenants(): Future[Seq[TenantDiscovery]] = {
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
              .toSeq  // FIXME try delete casting
          }
          .fold(Seq.empty[TenantDiscovery])(_ ++ _)
      }
      .mapConcat(_.to[collection.immutable.Seq])  // FIXME try delete casting
      .buffer(1, OverflowStrategy.backpressure)
      .via {
        Flow[TenantDiscovery]
          .mapAsync(1) { item =>
            onTenantDiscovery(item).map(_ => item)
          }
      }
      .runWith(Sink.seq)
  }

  // FIXME convert to graph stage and not future
  def exploreAccountNames(tenant: String): Future[Seq[AccountDiscovery]] = {
    Source
      .single(Paths.get(s"${primaryStorage}/t_${tenant}"))
      .via {
        Flow[Path]
          .map { file =>
            file
              .toFile
              .listFiles()
              .map(_.getName)
              .map { name => AccountDiscovery(tenant, name) }
              .toSeq  // FIXME try delete casting
          }
          .fold(Seq.empty[AccountDiscovery])(_ ++ _)
      }
      .mapConcat(_.to[collection.immutable.Seq])  // FIXME try delete casting
      .buffer(1, OverflowStrategy.backpressure)
      .via {
        Flow[AccountDiscovery]
          .mapAsync(1) { item =>
            onAccountDiscovery(item).map(_ => item)
          }
      }
      .runWith(Sink.seq)
  }

  // FIXME should not return Done but Tenant instead
  // FIXME rename to "acknowledge" something...
  private def onTenantDiscovery(item: TenantDiscovery): Future[Done] = {
    logger.info(s"explored tenant ${item}")
    // FIXME here notify SecondaryDataPersistorActor about this tenant
    // no questions asked
    Future.successful(Done)
  }

  // FIXME should not return Done but Account instead
  // FIXME rename to "acknowledge" something...
  private def onAccountDiscovery(item: AccountDiscovery): Future[Done] = {
    logger.info(s"explored account name ${item}")
    // FIXME there we need to load existing account from secondary data
    // to know if we need to gather meta data (if not found) and to obain
    // event pivot that we can then search from
    Future.successful(Done)
  }

}
