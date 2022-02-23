import sbt._

object Dependencies {

  /**
    * All the library dependencies listed below are already provided by the L0 Runtime and they shouldn't be compiled
    * into the assembled jar file, hence they're in a `Provided` scope. To compile a library into a jar file use a
    * `Compile` scope instead.
    */
  lazy val cats = Seq("cats-core")
    .map("org.typelevel" %% _ % "2.6.1" % Provided)

  lazy val catsEffect = Seq("cats-effect")
    .map("org.typelevel" %% _ % "3.2.9" % Provided)

  lazy val droste = Seq("droste-core", "droste-laws", "droste-macros")
    .map("io.higherkindness" %% _ % "0.8.0" % Provided)

  lazy val tessellation = Seq("tessellation-kernel", "tessellation-shared")
    .map("org.constellation" %% _ % "0.5.0-SNAPSHOT" % Provided)

  lazy val refined = Seq("refined")
    .map("eu.timepit" %% _ % "0.9.27" % Provided)

  lazy val decline = Seq("decline", "decline-refined")
    .map("com.monovore" %% _ % "2.2.0" % Provided)

}
