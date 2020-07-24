package com.openbank.dwh.persistence

import com.typesafe.config.Config
import slick.jdbc.JdbcBackend.Database
import slick.jdbc.JdbcCapabilities
import com.typesafe.scalalogging.StrictLogging
import slick.basic.Capability
import com.github.tminglei.slickpg._

trait PostgresProfile
    extends ExPostgresProfile
    with PgArraySupport
    with PgDate2Support
    with PgRangeSupport
    with PgHStoreSupport
    with PgSearchSupport
    with PgNetSupport
    with PgLTreeSupport {

  def pgjson = "jsonb"

  override protected def computeCapabilities: Set[Capability] =
    super.computeCapabilities + JdbcCapabilities.insertOrUpdate

  override val api = MyAPI

  object MyAPI
      extends API
      with ArrayImplicits
      with DateTimeImplicits
      with NetImplicits
      with LTreeImplicits
      with RangeImplicits
      with HStoreImplicits
      with SearchImplicits
      with SearchAssistants {
    implicit val strListTypeMapper =
      new SimpleArrayJdbcType[String]("text").to(_.toList)
  }
}

object Postgres {

  def forConfig(config: Config, namespace: String): Postgres = {
    val db = Database.forConfig(namespace, config)

    new Postgres(db)
  }

}

class Postgres(val database: Database)
    extends AutoCloseable
    with StrictLogging {

  override def close(): Unit = {
    logger.debug(s"closing datasource ${database}")
    database.close()
  }

  object PGProfile extends PostgresProfile

  val profile: PostgresProfile = PGProfile

}
