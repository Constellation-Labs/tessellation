name := "tessellation"
organization := "org.constellation"
version := "0.0.1"

scalaVersion := "2.13.1"

mainClass in assembly := Some("org.tessellation.App")

addCompilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1")

lazy val versions = new {
  val doobie = "0.9.0"
  val monocle = "2.0.3"
  val sqlite = "3.34.0"
  val twitterChill = "0.9.5"
  val http4s = "0.21.22" // 0.21.7
}

lazy val drosteDependencies = Seq(
  "io.higherkindness" %% "droste-core",
  "io.higherkindness" %% "droste-laws",
  "io.higherkindness" %% "droste-macros"
).map(_ % "0.8.0")

lazy val kryoDependencies = Seq(
  "com.twitter" %% "chill" % versions.twitterChill
)

lazy val fs2Dependencies = Seq(
  "co.fs2" %% "fs2-core",
  "co.fs2" %% "fs2-io",
  "co.fs2" %% "fs2-reactive-streams"
).map(_ % "2.5.9")

lazy val doobieDependencies = Seq(
  "org.tpolecat" %% "doobie-core",
  "org.tpolecat" %% "doobie-hikari",
  "org.tpolecat" %% "doobie-scalatest"
).map(_ % versions.doobie)

lazy val sqliteDependencies = Seq(
  "org.xerial" % "sqlite-jdbc" % versions.sqlite
)

lazy val monocleDependencies = Seq(
  "com.github.julien-truffaut" %% "monocle-core",
  "com.github.julien-truffaut" %% "monocle-macro"
).map(_ % versions.monocle)

lazy val http4sDependencies = Seq(
  "org.http4s" %% "http4s-dsl",
  "org.http4s" %% "http4s-blaze-client",
  "org.http4s" %% "http4s-blaze-server",
  "org.http4s" %% "http4s-prometheus-metrics",
  "org.http4s" %% "http4s-okhttp-client", // ???
  "org.http4s" %% "http4s-circe"
).map(_ % versions.http4s)

lazy val circeDependencies = Seq(
  "io.circe" %% "circe-core" % "0.13.0",
  "io.circe" %% "circe-generic" % "0.13.0",
  "io.circe" %% "circe-generic-extras" % "0.13.0",
  "io.circe" %% "circe-parser" % "0.13.0",
  "com.beachape" %% "enumeratum-circe" % "1.6.1"
)

lazy val pureconfigDependencies = Seq(
  "com.github.pureconfig" %% "pureconfig" % "0.13.0"
)

lazy val prometheusVersion = "0.9.0"
lazy val prometheusDependencies = Seq(
  "io.micrometer" % "micrometer-registry-prometheus" % "1.5.5",
  "io.prometheus" % "simpleclient" % prometheusVersion,
  "io.prometheus" % "simpleclient_common" % prometheusVersion,
  "io.prometheus" % "simpleclient_caffeine" % prometheusVersion,
  "io.prometheus" % "simpleclient_logback" % prometheusVersion
)

lazy val dependencies = Seq(
  "org.typelevel" %% "spire" % "0.17.0-M1",
  "org.typelevel" %% "cats-laws" % "2.0.0",
  "com.github.alexarchambault" %% "scalacheck-shapeless_1.14" % "1.2.3",
  "org.typelevel" %% "discipline-core" % "1.0.0",
  "org.typelevel" %% "discipline-scalatest" % "1.0.0",
  "org.typelevel" %% "cats-core" % "2.2.0",
  "org.tpolecat" %% "natchez-jaeger" % "0.0.12",
  ("org.typelevel" %% "cats-effect" % "2.2.0").withSources().withJavadoc(),
  "com.beachape" %% "enumeratum" % "1.6.1",
  "com.typesafe.scala-logging" %% "scala-logging" % "3.9.2",
  "ch.qos.logback" % "logback-classic" % "1.2.3",
  "io.chrisdavenport" %% "fuuid" % "0.5.0",
  "io.chrisdavenport" %% "log4cats-slf4j" % "1.1.1",
  "io.chrisdavenport" %% "mapref" % "0.1.1",
  ("com.github.blemale" %% "scaffeine" % "4.0.1").withSources().withJavadoc(),
  "joda-time" % "joda-time" % "1.6",
  "com.google.guava" % "guava" % "30.1.1-jre",
  "org.web3j" % "core" % "4.8.7",
  "org.web3j.eth2" % "beacon-node-api" % "1.0.0",
  "io.monix" %% "monix-catnap" % "3.4.0"
) ++ drosteDependencies ++ kryoDependencies ++ fs2Dependencies ++ doobieDependencies ++
  sqliteDependencies ++ monocleDependencies ++ http4sDependencies ++ circeDependencies ++ pureconfigDependencies ++
  prometheusDependencies

lazy val testDependencies = Seq(
  "org.scalacheck" %% "scalacheck" % "1.14.0",
  "org.scalatest" %% "scalatest" % "3.0.8",
  "org.scalactic" %% "scalactic" % "3.0.8",
  "org.scalamock" %% "scalamock" % "4.4.0",
  "org.mockito" %% "mockito-scala" % "1.5.16",
  "org.mockito" %% "mockito-scala-cats" % "1.5.16",
  "org.tpolecat" %% "doobie-specs2" % "0.9.0",
  "org.tpolecat" %% "doobie-scalatest" % "0.9.0"
).map(_ % "test")

libraryDependencies ++= dependencies ++ testDependencies

// scalac options come from the sbt-tpolecat plugin so need to set any here

addCompilerPlugin(("org.typelevel" %% "kind-projector" % "0.11.0").cross(CrossVersion.full))

scalacOptions ~= filterConsoleScalacOptions
