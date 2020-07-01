
name := "tessellation"
organization := "org.constellation"
version := "0.0.1-SNAPSHOT"

scalaVersion := "2.13.1"

lazy val dependencies = Seq(
  "io.higherkindness" %% "droste-core" % "0.8.0",
  "io.higherkindness" %% "droste-laws" % "0.8.0",
  "io.higherkindness" %% "droste-macros" % "0.8.0",
  "org.typelevel" %% "cats-laws" % "2.0.0",
  "com.github.alexarchambault" %% "scalacheck-shapeless_1.14" % "1.2.3",
  "org.typelevel" %% "discipline-core" % "1.0.0",
  "org.typelevel" %% "discipline-scalatest" % "1.0.0",
  "org.typelevel" %% "cats-core" % "2.0.0",
  ("org.typelevel" %% "cats-effect" % "2.0.0").withSources().withJavadoc(),
)

libraryDependencies ++= dependencies

// scalac options come from the sbt-tpolecat plugin so need to set any here

addCompilerPlugin("org.typelevel" %% "kind-projector" % "0.11.0" cross CrossVersion.full)

scalacOptions ~= filterConsoleScalacOptions