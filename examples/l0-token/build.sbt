import Dependencies._

resolvers += Resolver.mavenLocal

ThisBuild / scalaVersion := "2.13.6"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / organization := "org.example"
ThisBuild / organizationName := "example"

lazy val root = (project in file("."))
  .settings(
    name := "l0-token",
    libraryDependencies ++= List(
      Libraries.scalaTest,
      Libraries.catsCore,
      Libraries.catsEffect,
      Libraries.drosteCore,
      Libraries.drosteLaws,
      Libraries.drosteMacros,
      Libraries.tessellationShared,
      Libraries.tessellationKernel
    )
  )
