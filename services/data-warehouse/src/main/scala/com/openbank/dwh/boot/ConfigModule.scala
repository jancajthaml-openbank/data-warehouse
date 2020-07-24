package com.openbank.dwh.boot

import com.typesafe.config.{Config, ConfigFactory}

trait ConfigModule {

  def config: Config

}

trait GlobalConfigModule extends ConfigModule {

  override lazy val config: Config = ConfigFactory.load()

}
