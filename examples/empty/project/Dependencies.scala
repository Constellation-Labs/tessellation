import sbt._

object Dependencies {
  lazy val scalaTest = Seq("org.scalatest" %% "scalatest" % "3.2.8" % Test)

  lazy val cats = Seq(
    "org.typelevel" %% "cats-core" % "2.6.1",
    "org.typelevel" %% "cats-effect" % "3.2.9"
  )

  lazy val droste = Seq(
    "io.higherkindness" %% "droste-core",
    "io.higherkindness" %% "droste-laws",
    "io.higherkindness" %% "droste-macros"
  ).map(_ % "0.8.0")

  lazy val tessellationKernel = Seq(
    "org.constellation" %% "tessellation-kernel" % "0.0.1"
  )
}
