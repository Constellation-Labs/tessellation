name := "tessellation"
organization := "org.constellation"
version := "0.0.1"

scalaVersion := "2.13.1"

mainClass in assembly := Some("org.tessellation.App")

lazy val drosteDependencies = Seq(
  "io.higherkindness" %% "droste-core",
  "io.higherkindness" %% "droste-laws",
  "io.higherkindness" %% "droste-macros"
).map(_ % "0.8.0")

lazy val kryoDependencies = Seq(
  "com.twitter" %% "chill" % "0.9.5"
)

lazy val fs2Dependencies = Seq(
  "co.fs2" %% "fs2-core",
  "co.fs2" %% "fs2-io",
  "co.fs2" %% "fs2-reactive-streams"
).map(_ % "2.4.4")

lazy val doobieDependencies = Seq(
  "org.xerial" % "sqlite-jdbc" % "3.32.3.2",
  "org.tpolecat" %% "doobie-core" % "0.9.0",
  "org.tpolecat" %% "doobie-quill" % "0.9.0"
)

lazy val monocleDependencies = Seq(
  "com.github.julien-truffaut" %% "monocle-core" % "2.0.3",
  "com.github.julien-truffaut" %% "monocle-macro" % "2.0.3"
)

lazy val http4sDependencies = Seq(
  "org.http4s" %% "http4s-dsl" % http4sVersion,
  "org.http4s" %% "http4s-blaze-server" % http4sVersion,
  "org.http4s" %% "http4s-blaze-client" % http4sVersion,
  "org.http4s" %% "http4s-circe" % http4sVersion,
  "io.circe" %% "circe-core" % "0.13.0",
  "io.circe" %% "circe-generic" % "0.13.0",
  "io.circe" %% "circe-generic-extras" % "0.13.0",
  "io.circe" %% "circe-parser" % "0.13.0",
  "com.beachape" %% "enumeratum-circe" % "1.6.1",
  "org.http4s" %% "http4s-prometheus-metrics" % http4sVersion
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
  "io.chrisdavenport" %% "fuuid" % "0.5.0",
  "com.typesafe.scala-logging" %% "scala-logging" % "3.9.2",
  "ch.qos.logback" % "logback-classic" % "1.2.3",
  "io.chrisdavenport" %% "log4cats-slf4j" % "1.1.1",
  "com.github.pureconfig" %% "pureconfig" % "0.16.0",
  ("com.github.blemale" %% "scaffeine" % "4.0.1").withSources().withJavadoc(),
  "joda-time" % "joda-time" % "1.6",
  "com.google.guava" % "guava" % "30.1.1-jre"
) ++ drosteDependencies ++ kryoDependencies ++ fs2Dependencies ++ doobieDependencies ++ monocleDependencies ++ http4sDependencies ++ prometheusDependencies
val http4sVersion = "0.21.22"

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
