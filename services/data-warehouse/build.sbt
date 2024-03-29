organization := "com.openbank"
name := "data-warehouse"
version := "1.0.0"

scalaVersion := "2.13.7"

val akkaVersion = "2.6.17"
val akkaHttpVersion = "10.2.4"

val reactiveStreams = ExclusionRule("org.reactivestreams")
val slick = ExclusionRule("com.typesafe.slick")
val jna = ExclusionRule("net.java.dev.jna", "jna")
val log4j = ExclusionRule("org.slf4j")
val spray = ExclusionRule("io.spray")
val typesafeConfig = ExclusionRule("com.typesafe", "config")
val jnrConstants = ExclusionRule("com.typesafe", "config")
val commonLogging = ExclusionRule("com.github.jnr", "jnr-constants")

conflictManager := ConflictManager.strict

libraryDependencies ++= Seq(
	"com.typesafe" % "config" % "1.4.0",
  "com.typesafe" %% "ssl-config-core" % "0.4.2" excludeAll(typesafeConfig),
  "com.typesafe.akka" %% "akka-actor" % akkaVersion excludeAll(typesafeConfig),
  "com.typesafe.akka" %% "akka-actor-typed" % akkaVersion excludeAll(typesafeConfig),
  "com.typesafe.akka" %% "akka-stream" % akkaVersion excludeAll(typesafeConfig),
  "com.typesafe.akka" %% "akka-slf4j" % akkaVersion,
  "com.typesafe.akka" %% "akka-http" % akkaHttpVersion,
  "com.typesafe.akka" %% "akka-http-spray-json" % akkaHttpVersion,
  "com.typesafe.scala-logging" %% "scala-logging" % "3.9.4" excludeAll(log4j),
  "com.typesafe.slick" %% "slick" % "3.3.3" excludeAll(log4j, reactiveStreams, typesafeConfig),
  "com.typesafe.slick" %% "slick-hikaricp" % "3.3.3" excludeAll(log4j, reactiveStreams, typesafeConfig, slick),
  "com.github.tminglei" %% "slick-pg" % "0.19.7" excludeAll(log4j, reactiveStreams, typesafeConfig, slick),
  "ch.qos.logback" % "logback-classic" % "1.2.6" excludeAll(log4j),
  "org.slf4j" % "jcl-over-slf4j" % "1.7.32",
	"net.java.dev.jna" % "jna" % "5.9.0",
  "org.postgresql" %  "postgresql" % "42.2.19",
  "org.sangria-graphql" %% "sangria" % "2.0.1",
  "org.sangria-graphql" %% "sangria-spray-json" % "1.0.2" excludeAll(spray),
  "com.datadoghq" % "java-dogstatsd-client" % "2.13.0",
  "org.scalatest" %% "scalatest" % "3.2.10" % Test,
  "com.typesafe.akka" %% "akka-testkit" % akkaVersion % Test
)

excludeDependencies ++= Seq(commonLogging)

Global / transitiveClassifiers := Seq(Artifact.SourceClassifier)

Compile / scalacOptions := Seq(
  "-encoding",
  "utf-8",
  "-feature",
  "-unchecked",
  "-deprecation",
  "-Xlint",
  "-Xlint:inaccessible",
  "-Xlint:nullary-unit",
  "-Xlint:option-implicit",
  "-Xlint:package-object-classes",
  "-Xlint:poly-implicit-overload",
  "-Xlint:private-shadow",
  "-Xlint:missing-interpolator",
  "-Ywarn-dead-code",
  "-Ywarn-numeric-widen",
  "-Ywarn-value-discard",
  "-Ywarn-unused",
  "-Yrangepos"
)

Test / scalacOptions := Seq(
  "-Ywarn-unused"
)

enablePlugins(PackPlugin)

packGenerateWindowsBatFile := false

packMain := Map(
  name.value -> "com.openbank.dwh.Main"
)

scalafmtConfig := file(".scalafmt.conf")

scalafmtOnCompile := true
