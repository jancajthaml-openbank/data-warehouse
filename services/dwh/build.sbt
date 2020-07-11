organization := "com.openbank"
name := "dwh"
version := "1.0.0"

val scalaVersion = "2.12.8"

val akkaVersion = "2.6.4"
val akkaHttpVersion = "10.1.11"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % akkaVersion,
  "com.typesafe.akka" %% "akka-actor-typed" % akkaVersion,
  "com.typesafe.akka" %% "akka-slf4j" % akkaVersion,
  "com.typesafe.akka" %% "akka-http" % akkaHttpVersion,
  "com.typesafe.akka" %% "akka-http-spray-json" % akkaHttpVersion,
  "com.typesafe.akka" %% "akka-stream" % akkaVersion,
  "com.typesafe.scala-logging" %% "scala-logging" % "3.9.2",
  "ch.qos.logback" % "logback-classic" % "1.2.3",
  "org.slf4j" % "jcl-over-slf4j" % "1.7.25",
  "org.postgresql" %  "postgresql" % "9.4.1212",
  "com.github.tminglei" %% "slick-pg" % "0.19.0",
  "com.typesafe.slick" %% "slick" % "3.3.1",
  "com.typesafe.slick" %% "slick-hikaricp" % "3.2.0",
  "org.sangria-graphql" %% "sangria" % "2.0.0",
  "org.sangria-graphql" %% "sangria-spray-json" % "1.0.2",
  "org.sangria-graphql" %% "sangria-akka-streams" % "1.0.2"
)

enablePlugins(PackPlugin)
