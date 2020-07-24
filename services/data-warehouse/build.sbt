organization := "com.openbank"
name := "data-warehouse"
version := "1.0.0"

scalaVersion := "2.13.1"

val akkaVersion = "2.6.8"
val akkaHttpVersion = "10.1.12"

val reaciverStreams = ExclusionRule("org.reactivestreams", "reactive-streams")
val log4j = ExclusionRule("org.slf4j")
val typesafeConfig = ExclusionRule("com.typesafe", "config")
val commonLogging = ExclusionRule("commons-logging", "commons-logging")

conflictManager := ConflictManager.all

libraryDependencies ++= Seq(
  "com.typesafe" % "config" % "1.4.0",
  "com.typesafe" %% "ssl-config-core" % "0.4.1" excludeAll(typesafeConfig),
  "com.typesafe.akka" %% "akka-actor" % akkaVersion excludeAll(typesafeConfig),
  "com.typesafe.akka" %% "akka-actor-typed" % akkaVersion excludeAll(typesafeConfig),
  "com.typesafe.akka" %% "akka-slf4j" % akkaVersion,
  "com.typesafe.akka" %% "akka-http" % akkaHttpVersion,
  "com.typesafe.akka" %% "akka-http-spray-json" % akkaHttpVersion,
  "com.typesafe.akka" %% "akka-stream" % akkaVersion excludeAll(typesafeConfig),
  "com.typesafe.scala-logging" %% "scala-logging" % "3.9.2" excludeAll(log4j),
  "com.typesafe.slick" %% "slick" % "3.3.2" excludeAll(typesafeConfig, reaciverStreams),
  "com.typesafe.slick" %% "slick-hikaricp" % "3.3.2" excludeAll(log4j),
  "ch.qos.logback" % "logback-classic" % "1.2.3" excludeAll(log4j),
  "org.slf4j" % "jcl-over-slf4j" % "1.7.30",
  "org.postgresql" %  "postgresql" % "42.2.5",
  "com.github.tminglei" %% "slick-pg" % "0.19.0",
  "org.sangria-graphql" %% "sangria" % "2.0.0",
  "org.sangria-graphql" %% "sangria-spray-json" % "1.0.2",
  "org.scalatest" %% "scalatest" % "3.1.1" % Test,
  "com.typesafe.akka" %% "akka-testkit" % akkaVersion % Test
)

excludeDependencies ++= Seq(commonLogging)

scalacOptions in Compile := Seq(
  "-encoding",
  "utf-8",
  "-feature",
  "-unchecked",
  "-deprecation"
)

enablePlugins(PackPlugin)

packGenerateWindowsBatFile := false

packMain := Map(
  name.value -> "com.openbank.dwh.Main"
)

packJvmOpts := Map(
  name.value -> Seq("-XX:+HeapDumpOnOutOfMemoryError", "-XX:+UseGCOverheadLimit")
)
