package com.openbank.dwh.persistence

import slick.jdbc.JdbcBackend.Database
import slick.jdbc.JdbcProfile
import java.sql.Connection


trait Persistence extends AutoCloseable {

  def database: Database
  val profile: JdbcProfile

  override def close(): Unit = database.close()

}


class Postgres(val database: Database) extends Persistence {

  object PGProfile extends JdbcProfile

  override val profile: JdbcProfile = PGProfile

}

