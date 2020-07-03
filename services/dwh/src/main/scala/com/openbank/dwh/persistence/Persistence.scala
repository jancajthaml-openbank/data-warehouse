package com.openbank.dwh.persistence

import com.typesafe.config.Config
import slick.jdbc.JdbcBackend.Database
import slick.jdbc.JdbcProfile
import org.postgresql.PGConnection
import scala.util.Try
import org.postgresql.core.BaseConnection


trait ConnectionProvider {
  def acquire(): Try[PGConnection]
  def release(exOpt: Option[Throwable]): Unit
}


trait Persistence extends AutoCloseable {

  def database: Database
  val profile: JdbcProfile
  def provider: ConnectionProvider

  override def close(): Unit = database.close()
}


class Postgres(val database: Database) extends Persistence {

  object PGProfile extends JdbcProfile

  override val profile: JdbcProfile = PGProfile

  override def provider: ConnectionProvider = new ConnectionProvider {
    private val session = database.createSession()

    def acquire(): Try[PGConnection] = Try {
      session.conn.unwrap(classOf[PGConnection]).asInstanceOf[PGConnection]
    }
    def release(exOpt: Option[Throwable]): Unit = session.close()
  }
}

