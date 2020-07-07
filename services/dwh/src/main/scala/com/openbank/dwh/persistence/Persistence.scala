package com.openbank.dwh.persistence

import com.typesafe.config.Config
import slick.jdbc.JdbcBackend.Database
import slick.jdbc.JdbcProfile
import com.mchange.v2.c3p0.ComboPooledDataSource
import slick.util.AsyncExecutor
import scala.concurrent.duration._
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
    val ds = new ComboPooledDataSource
    ds.setDriverClass("org.postgresql.Driver")
    ds.setJdbcUrl(config.getString("persistence-secondary.postgresql.url"))
    ds.setUser(config.getString("persistence-secondary.postgresql.user"))
    ds.setPassword(config.getString("persistence-secondary.postgresql.password"))
    ds.setMinPoolSize(20)
    ds.setPreferredTestQuery("SELECT 1")
    ds.setIdleConnectionTestPeriod(300)
    ds.setMaxPoolSize(100)
    ds.setInitialPoolSize(ds.getMinPoolSize)
    ds.setAcquireIncrement(1)
    ds.setNumHelperThreads(10)
    ds.setMaxStatements(100)
    ds.setMaxStatementsPerConnection(10)
    ds.setTestConnectionOnCheckin(false)
    ds.setTestConnectionOnCheckout(false)

    new Postgres(Database.forDataSource(ds, None))
  }

}


class Postgres(val database: Database) extends Persistence {

  object PGProfile extends JdbcProfile

  override val profile: JdbcProfile = PGProfile

}
