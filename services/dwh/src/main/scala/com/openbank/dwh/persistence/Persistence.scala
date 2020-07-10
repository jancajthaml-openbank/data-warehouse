package com.openbank.dwh.persistence

import com.typesafe.config.Config
import slick.jdbc.JdbcBackend.Database
import slick.jdbc.JdbcProfile
import com.typesafe.scalalogging.LazyLogging


// FIXME not really generic should be named Database (support H2)
trait Persistence extends AutoCloseable with LazyLogging {

  def database: Database
  val profile: JdbcProfile

  override def close(): Unit = {
    logger.debug(s"closing datasource ${database}")
    database.close()
  }

}


object Postgres {

  def forConfig(config: Config): Postgres ={
    val db = Database.forConfig("persistence-secondary.postgresql", config)

    new Postgres(db)
  }

}


class Postgres(val database: Database) extends Persistence {

  object PGProfile extends JdbcProfile

  override val profile: JdbcProfile = PGProfile

}
