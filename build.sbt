import Dependencies.{Libraries, _}

ThisBuild / scalaVersion := "2.13.5"
ThisBuild / version := "2.0.0"
ThisBuild / organization := "org.constellation"
ThisBuild / organizationName := "tesselation"

ThisBuild / evictionErrorLevel := Level.Warn
ThisBuild / scalafixDependencies += Libraries.organizeImports

resolvers += Resolver.sonatypeRepo("snapshots")

val scalafixCommonSettings = inConfig(IntegrationTest)(scalafixConfigSettings(IntegrationTest))

lazy val root = (project in file("."))
  .settings(
    name := "tesselation"
  )
  .aggregate(keytool, shared, core, tests)

lazy val keytool = (project in file("modules/keytool"))
  .enablePlugins(AshScriptPlugin)
  .settings(
    name := "tesselation-keytool",
    scalacOptions ++= List("-Ymacro-annotations", "-Yrangepos", "-Wconf:cat=unused:info"),
    scalafmtOnCompile := true,
    scalafixOnCompile := true,
    resolvers += Resolver.sonatypeRepo("snapshots"),
    Defaults.itSettings,
    scalafixCommonSettings,
    makeBatScripts := Seq(),
    libraryDependencies ++= Seq(
      CompilerPlugin.kindProjector,
      CompilerPlugin.betterMonadicFor,
      CompilerPlugin.semanticDB,
      Libraries.bouncyCastle,
      Libraries.cats,
      Libraries.circeCore,
      Libraries.circeGeneric,
      Libraries.circeParser,
      Libraries.circeRefined,
      Libraries.cirisCore,
      Libraries.cirisEnum,
      Libraries.cirisRefined,
      Libraries.comcast,
      Libraries.derevoCore,
      Libraries.derevoCats,
      Libraries.derevoCirce,
      Libraries.log4cats,
      Libraries.logback % Runtime,
      Libraries.monocleCore,
      Libraries.newtype,
      Libraries.scCore,
      Libraries.scProv,
      Libraries.scBcpkix,
      Libraries.scBcpg,
      Libraries.scBctls,
      Libraries.scopt,
      Libraries.refinedCore,
      Libraries.refinedCats
    )
  )

lazy val shared = (project in file("modules/shared"))
  .enablePlugins(AshScriptPlugin)
  .settings(
    name := "tesselation-shared",
    scalacOptions ++= List("-Ymacro-annotations", "-Yrangepos", "-Wconf:cat=unused:info"),
    scalafmtOnCompile := true,
    scalafixOnCompile := true,
    resolvers += Resolver.sonatypeRepo("snapshots"),
    Defaults.itSettings,
    scalafixCommonSettings,
    makeBatScripts := Seq(),
    libraryDependencies ++= Seq(
      CompilerPlugin.kindProjector,
      CompilerPlugin.betterMonadicFor,
      CompilerPlugin.semanticDB,
      Libraries.cats,
      Libraries.circeCore,
      Libraries.circeGeneric,
      Libraries.circeParser,
      Libraries.circeRefined,
      Libraries.cirisCore,
      Libraries.cirisEnum,
      Libraries.cirisRefined,
      Libraries.comcast,
      Libraries.derevoCore,
      Libraries.derevoCats,
      Libraries.derevoCirce,
      Libraries.monocleCore,
      Libraries.newtype,
      Libraries.refinedCore,
      Libraries.refinedCats
    )
  )

lazy val tests = (project in file("modules/tests"))
  .configs(IntegrationTest)
  .settings(
    name := "tesselation-test-suite",
    scalacOptions ++= List("-Ymacro-annotations", "-Yrangepos", "-Wconf:cat=unused:info"),
    testFrameworks += new TestFramework("weaver.framework.CatsEffect"),
    Defaults.itSettings,
    scalafixCommonSettings,
    libraryDependencies ++= Seq(
      CompilerPlugin.kindProjector,
      CompilerPlugin.betterMonadicFor,
      CompilerPlugin.semanticDB,
      Libraries.catsLaws,
      Libraries.log4catsNoOp,
      Libraries.monocleLaw,
      Libraries.refinedScalacheck,
      Libraries.weaverCats,
      Libraries.weaverDiscipline,
      Libraries.weaverScalaCheck
    )
  )
  .dependsOn(core)

lazy val core = (project in file("modules/core"))
  .enablePlugins(AshScriptPlugin)
  .dependsOn(keytool, shared)
  .settings(
    name := "tesselation-core",
    scalacOptions ++= List("-Ymacro-annotations", "-Yrangepos", "-Wconf:cat=unused:info"),
    scalafmtOnCompile := true,
    scalafixOnCompile := true,
    resolvers += Resolver.sonatypeRepo("snapshots"),
    Defaults.itSettings,
    scalafixCommonSettings,
    makeBatScripts := Seq(),
    libraryDependencies ++= Seq(
      CompilerPlugin.kindProjector,
      CompilerPlugin.betterMonadicFor,
      CompilerPlugin.semanticDB,
      Libraries.cats,
      Libraries.catsEffect,
      //Libraries.catsRetry,
      Libraries.circeCore,
      Libraries.circeGeneric,
      Libraries.circeParser,
      Libraries.circeRefined,
      Libraries.cirisCore,
      Libraries.cirisEnum,
      Libraries.cirisRefined,
      Libraries.derevoCore,
      Libraries.derevoCats,
      Libraries.derevoCirce,
      Libraries.doobieCore,
      Libraries.doobieQuill,
      Libraries.drosteCore,
      Libraries.drosteLaws,
      Libraries.drosteMacros,
      Libraries.fs2,
      Libraries.http4sDsl,
      Libraries.http4sServer,
      Libraries.http4sClient,
      Libraries.http4sCirce,
      Libraries.http4sJwtAuth,
      Libraries.javaxCrypto,
      Libraries.log4cats,
      Libraries.logback % Runtime,
      Libraries.monocleCore,
      Libraries.newtype,
      Libraries.redis4catsEffects,
      Libraries.redis4catsLog4cats,
      Libraries.refinedCore,
      Libraries.refinedCats,
      Libraries.skunkCore,
      Libraries.skunkCirce,
      Libraries.squants
    )
  )

addCommandAlias("runLinter", ";scalafixAll --rules OrganizeImports")
