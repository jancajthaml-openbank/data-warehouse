package com.openbank.dwh.boot

import com.typesafe.config.{Config, ConfigFactory}

trait ConfigModule {

  def config: Config

}

trait ProductionConfigModule extends ConfigModule {

  lazy val config: Config = ConfigFactory.load()

}
