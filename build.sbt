import Dependencies._

ThisBuild / scalaVersion := "2.13.8"
ThisBuild / organization := "org.constellation"
ThisBuild / organizationName := "tessellation"

ThisBuild / evictionErrorLevel := Level.Warn
ThisBuild / scalafixDependencies += Libraries.organizeImports

resolvers += Resolver.sonatypeRepo("snapshots")

val scalafixCommonSettings = inConfig(IntegrationTest)(scalafixConfigSettings(IntegrationTest))

bloopExportJarClassifiers in Global := Some(Set("sources"))

lazy val commonSettings = Seq(
  scalacOptions ++= List("-Ymacro-annotations", "-Yrangepos", "-Wconf:cat=unused:info", "-language:reflectiveCalls"),
  scalafmtOnCompile := true,
  scalafixOnCompile := true,
  resolvers ++= List(
    Resolver.sonatypeRepo("snapshots"),
    Resolver.githubPackages("abankowski", "http-request-signer")
  )
)

lazy val commonTestSettings = Seq(
  testFrameworks += new TestFramework("weaver.framework.CatsEffect"),
  libraryDependencies ++= Seq(
    Libraries.weaverCats,
    Libraries.weaverDiscipline,
    Libraries.weaverScalaCheck,
    Libraries.catsEffectTestkit
  ).map(_ % Test)
)

ThisBuild / assemblyMergeStrategy := {
  case "logback.xml"                                       => MergeStrategy.first
  case x if x.contains("io.netty.versions.properties")     => MergeStrategy.discard
  case PathList(xs @ _*) if xs.last == "module-info.class" => MergeStrategy.first
  case x =>
    val oldStrategy = (assembly / assemblyMergeStrategy).value
    oldStrategy(x)
}

Global / fork := true
Global / cancelable := true
Global / onChangedBuildSource := ReloadOnSourceChanges

lazy val dockerSettings = Seq(
  Docker / packageName := "tessellation",
  dockerBaseImage := "openjdk:jre-alpine"
)

lazy val root = (project in file("."))
  .settings(
    name := "tessellation"
  )
  .aggregate(shared, testShared, keytool, kernel, core, wallet, dagShared, sdk, dagL1)

lazy val shared = (project in file("modules/shared"))
  .enablePlugins(AshScriptPlugin)
  .enablePlugins(BuildInfoPlugin)
  .dependsOn(testShared % Test)
  .settings(
    name := "tessellation-shared",
    buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion),
    buildInfoPackage := "org.tessellation",
    Defaults.itSettings,
    scalafixCommonSettings,
    commonSettings,
    commonTestSettings,
    makeBatScripts := Seq(),
    libraryDependencies ++= Seq(
      CompilerPlugin.kindProjector,
      CompilerPlugin.betterMonadicFor,
      CompilerPlugin.semanticDB,
      Libraries.bc,
      Libraries.bcExtensions,
      Libraries.betterFiles,
      Libraries.cats,
      Libraries.catsEffect,
      Libraries.chill,
      Libraries.circeCore,
      Libraries.circeGeneric,
      Libraries.circeParser,
      Libraries.circeRefined,
      Libraries.cirisCore,
      Libraries.cirisEnum,
      Libraries.cirisRefined,
      Libraries.comcast,
      Libraries.declineCore,
      Libraries.declineEffect,
      Libraries.declineRefined,
      Libraries.derevoCore,
      Libraries.derevoCats,
      Libraries.derevoCirce,
      Libraries.derevoScalacheck,
      Libraries.doobieQuill,
      Libraries.enumeratumCore,
      Libraries.enumeratumCirce,
      Libraries.guava,
      Libraries.log4cats,
      Libraries.logback % Runtime,
      Libraries.logstashLogbackEncoder % Runtime,
      Libraries.mapref,
      Libraries.monocleCore,
      Libraries.monocleMacro,
      Libraries.newtype,
      Libraries.refinedCore,
      Libraries.refinedCats,
      Libraries.refinedScalacheck,
      Libraries.http4sCore
    )
  )

lazy val testShared = (project in file("modules/test-shared"))
  .configs(IntegrationTest)
  .settings(
    name := "tessellation-test-shared",
    Defaults.itSettings,
    scalafixCommonSettings,
    commonSettings,
    commonTestSettings,
    libraryDependencies ++= Seq(
      CompilerPlugin.kindProjector,
      CompilerPlugin.betterMonadicFor,
      CompilerPlugin.semanticDB,
      Libraries.catsLaws,
      Libraries.log4catsNoOp,
      Libraries.monocleLaw,
      Libraries.refinedScalacheck,
      Libraries.cats,
      Libraries.catsEffect,
      Libraries.circeCore,
      Libraries.circeGeneric,
      Libraries.circeParser,
      Libraries.circeRefined,
      Libraries.fs2Core,
      Libraries.http4sDsl,
      Libraries.http4sServer,
      Libraries.http4sClient,
      Libraries.http4sCirce,
      Libraries.http4sJwtAuth,
      Libraries.weaverCats,
      Libraries.weaverDiscipline,
      Libraries.weaverScalaCheck
    )
  )

lazy val schema = (project in file("modules/schema"))
  .enablePlugins(AshScriptPlugin)
  .enablePlugins(BuildInfoPlugin)
  .dependsOn(shared, testShared % Test)
  .settings(
    name := "tessellation-schema",
    buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion),
    buildInfoPackage := "org.tessellation",
    Defaults.itSettings,
    scalafixCommonSettings,
    commonSettings,
    commonTestSettings,
    makeBatScripts := Seq(),
    libraryDependencies ++= Seq(
      CompilerPlugin.kindProjector,
      CompilerPlugin.betterMonadicFor,
      CompilerPlugin.semanticDB,
    )
  )

lazy val kernel = (project in file("modules/kernel"))
  .enablePlugins(AshScriptPlugin)
  .dependsOn(schema % "compile->compile;test->test", testShared % Test)
  .settings(
    name := "tessellation-kernel",
    Defaults.itSettings,
    scalafixCommonSettings,
    commonSettings,
    commonTestSettings,
    makeBatScripts := Seq(),
    libraryDependencies ++= Seq(
      CompilerPlugin.kindProjector,
      CompilerPlugin.semanticDB,
      Libraries.drosteCore,
      Libraries.fs2Core
    )
  )

lazy val keytool = (project in file("modules/keytool"))
  .enablePlugins(AshScriptPlugin)
  .dependsOn(schema)
  .settings(
    name := "tessellation-keytool",
    Defaults.itSettings,
    scalafixCommonSettings,
    commonSettings,
    commonTestSettings,
    makeBatScripts := Seq(),
    libraryDependencies ++= Seq(
      CompilerPlugin.kindProjector,
      CompilerPlugin.betterMonadicFor,
      CompilerPlugin.semanticDB,
      Libraries.bc,
      Libraries.bcExtensions,
      Libraries.cats,
      Libraries.catsEffect,
      Libraries.circeCore,
      Libraries.circeGeneric,
      Libraries.circeParser,
      Libraries.circeRefined,
      Libraries.cirisCore,
      Libraries.comcast,
      Libraries.derevoCore,
      Libraries.derevoCats,
      Libraries.derevoCirce,
      Libraries.fs2IO,
      Libraries.log4cats,
      Libraries.logback % Runtime,
      Libraries.logstashLogbackEncoder % Runtime,
      Libraries.monocleCore,
      Libraries.monocleMacro,
      Libraries.newtype,
      Libraries.declineCore,
      Libraries.declineEffect,
      Libraries.declineRefined,
      Libraries.refinedCore,
      Libraries.refinedCats
    )
  )

lazy val wallet = (project in file("modules/wallet"))
  .enablePlugins(AshScriptPlugin)
  .dependsOn(keytool)
  .settings(
    name := "tessellation-wallet",
    Defaults.itSettings,
    scalafixCommonSettings,
    commonSettings,
    commonTestSettings,
    makeBatScripts := Seq(),
    libraryDependencies ++= Seq(
      CompilerPlugin.kindProjector,
      CompilerPlugin.betterMonadicFor,
      CompilerPlugin.semanticDB,
      Libraries.circeFs2,
      Libraries.cats,
      Libraries.catsEffect,
      Libraries.cirisCore,
      Libraries.declineCore,
      Libraries.declineEffect,
      Libraries.declineRefined,
      Libraries.fs2IO,
      Libraries.refinedCore,
      Libraries.refinedCats,
      Libraries.log4cats,
      Libraries.logback % Runtime,
      Libraries.logstashLogbackEncoder % Runtime
    )
  )

lazy val sdk = (project in file("modules/sdk"))
  .dependsOn(schema % "compile->compile;test->test", testShared % Test, keytool, dagShared)
  .configs(IntegrationTest)
  .settings(
    name := "tessellation-sdk",
    Defaults.itSettings,
    scalafixCommonSettings,
    commonSettings,
    commonTestSettings,
    libraryDependencies ++= Seq(
      CompilerPlugin.kindProjector,
      CompilerPlugin.betterMonadicFor,
      CompilerPlugin.semanticDB,
      Libraries.cats,
      Libraries.catsEffect,
      Libraries.catsRetry,
      Libraries.circeCore,
      Libraries.circeGeneric,
      Libraries.circeParser,
      Libraries.circeRefined,
      Libraries.circeShapes,
      Libraries.derevoCore,
      Libraries.derevoCats,
      Libraries.derevoCirce,
      Libraries.fs2Core,
      Libraries.fs2DataCsv,
      Libraries.fs2DataCsvGeneric,
      Libraries.http4sCore,
      Libraries.http4sDsl,
      Libraries.http4sServer,
      Libraries.http4sClient,
      Libraries.http4sCirce,
      Libraries.http4sJwtAuth,
      Libraries.httpSignerCore,
      Libraries.httpSignerHttp4s,
      Libraries.jawnParser,
      Libraries.jawnAst,
      Libraries.jawnFs2,
      Libraries.declineCore,
      Libraries.declineEffect,
      Libraries.declineRefined,
      Libraries.logback,
      Libraries.logstashLogbackEncoder % Runtime,
      Libraries.log4cats,
      Libraries.micrometerPrometheusRegistry,
      Libraries.shapeless
    )
  )

lazy val dagShared = (project in file("modules/dag-shared"))
  .dependsOn(schema % "compile->compile;test->test", keytool, testShared % Test)
  .settings(
    name := "tessellation-dag-shared",
    Defaults.itSettings,
    scalafixCommonSettings,
    commonSettings,
    commonTestSettings,
    makeBatScripts := Seq(),
    libraryDependencies := Seq(
      CompilerPlugin.kindProjector,
      CompilerPlugin.betterMonadicFor,
      CompilerPlugin.semanticDB,
      Libraries.logback % Runtime,
      Libraries.logstashLogbackEncoder % Runtime
    )
  )

lazy val dagL1 = (project in file("modules/dag-l1"))
  .enablePlugins(AshScriptPlugin)
  .enablePlugins(JavaAppPackaging)
  .dependsOn(dagShared % "compile->compile;test->test", sdk, kernel, testShared % Test)
  .configs(IntegrationTest)
  .settings(
    name := "tessellation-dag-l1",
    Defaults.itSettings,
    scalafixCommonSettings,
    commonSettings,
    commonTestSettings,
    dockerSettings,
    libraryDependencies ++= Seq(
      CompilerPlugin.kindProjector,
      CompilerPlugin.betterMonadicFor,
      CompilerPlugin.semanticDB,
      Libraries.cats,
      Libraries.catsEffect,
      Libraries.circeShapes,
      Libraries.derevoCats,
      Libraries.derevoCirce,
      Libraries.derevoCore,
      Libraries.doobieCore,
      Libraries.doobieHikari,
      Libraries.doobieQuill,
      Libraries.drosteCore,
      Libraries.flyway,
      Libraries.fs2Core,
      Libraries.fs2DataCsv,
      Libraries.fs2DataCsvGeneric,
      Libraries.mapref,
      Libraries.monocleCore,
      Libraries.monocleMacro,
      Libraries.newtype,
      Libraries.refinedCore,
      Libraries.sqlite
    )
  )

lazy val tools = (project in file("modules/tools"))
  .enablePlugins(AshScriptPlugin)
  .enablePlugins(JavaAppPackaging)
  .dependsOn(core, dagL1)
  .settings(
    name := "tessellation-tools",
    Defaults.itSettings,
    scalafixCommonSettings,
    commonSettings,
    commonTestSettings,
    run / connectInput := true,
    libraryDependencies ++= Seq(
      CompilerPlugin.kindProjector,
      CompilerPlugin.betterMonadicFor,
      CompilerPlugin.semanticDB,
      Libraries.cats,
      Libraries.catsEffect,
      Libraries.circeCore,
      Libraries.circeGeneric,
      Libraries.circeParser,
      Libraries.circeRefined,
      Libraries.circeShapes,
      Libraries.cirisCore,
      Libraries.cirisEnum,
      Libraries.cirisRefined,
      Libraries.derevoCore,
      Libraries.derevoCats,
      Libraries.derevoCirce,
      Libraries.fs2Core,
      Libraries.fs2DataCsv,
      Libraries.fs2DataCsvGeneric,
      Libraries.http4sDsl,
      Libraries.http4sClient,
      Libraries.http4sCirce,
      Libraries.log4cats,
      Libraries.logback % Runtime,
      Libraries.logstashLogbackEncoder % Runtime,
      Libraries.mapref,
      Libraries.monocleCore,
      Libraries.newtype,
      Libraries.refinedCore,
      Libraries.refinedCats,
      Libraries.skunkCore,
      Libraries.skunkCirce
    )
  )

lazy val core = (project in file("modules/core"))
  .enablePlugins(AshScriptPlugin)
  .enablePlugins(JavaAppPackaging)
  .dependsOn(keytool, kernel, shared % "compile->compile;test->test", testShared % Test, dagShared % "compile->compile;test->test", sdk)
  .settings(
    name := "tessellation-core",
    Defaults.itSettings,
    scalafixCommonSettings,
    commonSettings,
    commonTestSettings,
    dockerSettings,
    makeBatScripts := Seq(),
    libraryDependencies ++= Seq(
      CompilerPlugin.kindProjector,
      CompilerPlugin.betterMonadicFor,
      CompilerPlugin.semanticDB,
      Libraries.cats,
      Libraries.catsEffect,
      Libraries.catsRetry,
      Libraries.circeCore,
      Libraries.circeGeneric,
      Libraries.circeParser,
      Libraries.circeRefined,
      Libraries.circeShapes,
      Libraries.cirisCore,
      Libraries.cirisEnum,
      Libraries.cirisRefined,
      Libraries.derevoCore,
      Libraries.derevoCats,
      Libraries.derevoCirce,
      Libraries.doobieCore,
      Libraries.doobieHikari,
      Libraries.doobieH2,
      Libraries.doobieQuill,
      Libraries.drosteCore,
      Libraries.drosteLaws,
      Libraries.drosteMacros,
      Libraries.fs2Core,
      Libraries.flyway,
      Libraries.fs2DataCsv,
      Libraries.fs2DataCsvGeneric,
      Libraries.h2,
      Libraries.http4sDsl,
      Libraries.http4sServer,
      Libraries.http4sClient,
      Libraries.http4sCirce,
      Libraries.http4sJwtAuth,
      Libraries.httpSignerCore,
      Libraries.httpSignerHttp4s,
      Libraries.javaxCrypto,
      Libraries.log4cats,
      Libraries.logback % Runtime,
      Libraries.logstashLogbackEncoder % Runtime,
      Libraries.mapref,
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

lazy val currencyL0 = (project in file("modules/currency-l0"))
  .enablePlugins(AshScriptPlugin)
  .enablePlugins(JavaAppPackaging)
  .dependsOn(keytool, kernel, shared % "compile->compile;test->test", testShared % Test, dagShared % "compile->compile;test->test", sdk)
  .settings(
    name := "tessellation-currency-l0",
    Defaults.itSettings,
    scalafixCommonSettings,
    commonSettings,
    commonTestSettings,
    dockerSettings,
    makeBatScripts := Seq(),
    libraryDependencies ++= Seq(
      CompilerPlugin.kindProjector,
      CompilerPlugin.betterMonadicFor,
      CompilerPlugin.semanticDB,
      Libraries.refinedCore,
      Libraries.refinedCats,
    )
  )

addCommandAlias("runLinter", ";scalafixAll --rules OrganizeImports")
