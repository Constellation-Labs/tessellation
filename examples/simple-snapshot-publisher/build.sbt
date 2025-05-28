import Dependencies._
import sbt._

resolvers += Resolver.mavenLocal

ThisBuild / scalaVersion := "2.13.6"
ThisBuild / version := "0.1.0"
ThisBuild / organization := "org.example"
ThisBuild / organizationName := "example"

lazy val generateInput = inputKey[Unit]("Generates a series of serialized inputs")

generateInput := {
  (Compile / run).evaluated
}

lazy val root = (project in file("."))
  .settings(
    name := "simple-snapshot-publisher",
    libraryDependencies ++= cats ++ catsEffect ++ droste ++ refined ++ tessellation ++ decline,
    assemblyPackageScala / assembleArtifact := false,
    /** Exclude Scala Standard Library from an assembly jar file */
    Compile / run := Defaults
      .runTask(Compile / fullClasspath, Compile / run / mainClass, Compile / run / runner)
      .evaluated
    /** Include dependencies in the `Provided` scope when running a `generateInput` task */
  )
