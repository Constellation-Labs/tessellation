import sbt._

object Dependencies {
  lazy val scalaTest = Seq("org.scalatest" %% "scalatest" % "3.2.8" % Test)

  lazy val cats = Seq("org.typelevel" %% "cats-core" % "2.6.1" % Provided)

  lazy val catsEffect = Seq("org.typelevel" %% "cats-effect" % "3.2.9" % Provided)

  lazy val droste = Seq("droste-core", "droste-laws", "droste-macros")
    .map("io.higherkindness" %% _ % "0.8.0" % Provided)

  lazy val tessellation = Seq("tessellation-kernel", "tessellation-shared")
    .map("org.constellation" %% _ % "0.5.0-SNAPSHOT" % Provided)

  lazy val refined = Seq("eu.timepit" %% "refined" % "0.9.27" % Provided)
}
