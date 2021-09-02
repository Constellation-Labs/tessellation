import sbt._

object Dependencies {
  lazy val scalaTest = Seq("org.scalatest" %% "scalatest" % "3.2.8" % Test)

  lazy val cats = Seq(
    "org.typelevel" %% "cats-core",
    "org.typelevel" %% "cats-effect"
  ).map(_ % "2.2.0")

  lazy val droste = Seq(
    "io.higherkindness" %% "droste-core",
    "io.higherkindness" %% "droste-laws",
    "io.higherkindness" %% "droste-macros"
  ).map(_ % "0.8.0")

  lazy val tessellation = Seq(
    "org.constellation" %% "tessellation" % "0.0.1-SNAPSHOT"
  )
}
