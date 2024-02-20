import Dependencies._

ThisBuild / scalaVersion := "2.13.12"
ThisBuild / organization := "org.constellation"
ThisBuild / organizationName := "tessellation"

ThisBuild / evictionErrorLevel := Level.Warn
ThisBuild / scalafixDependencies += Libraries.organizeImports

resolvers += Resolver.sonatypeRepo("snapshots")

val scalafixCommonSettings = inConfig(IntegrationTest)(scalafixConfigSettings(IntegrationTest))

bloopExportJarClassifiers in Global := Some(Set("sources"))

val ghTokenSource = TokenSource.GitConfig("github.token") || TokenSource.Environment("GITHUB_TOKEN")

githubTokenSource := ghTokenSource

lazy val commonSettings = Seq(
  scalacOptions ++= List("-Ymacro-annotations", "-Yrangepos", "-Wconf:cat=unused:info", "-language:reflectiveCalls"),
  scalafmtOnCompile := true,
  scalafixOnCompile := true,
  resolvers ++= List(
    Resolver.sonatypeRepo("snapshots"),
    Resolver.githubPackages("abankowski", "http-request-signer")
  ),
  githubTokenSource := ghTokenSource
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
  .aggregate(testShared, shared, keytool, kernel, wallet, nodeShared, sdk, dagL0, dagL1, currencyL0, currencyL1, tools, rosetta)

lazy val kernel = (project in file("modules/kernel"))
  .enablePlugins(AshScriptPlugin)
  .dependsOn(shared, testShared % Test)
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

lazy val wallet = (project in file("modules/wallet"))
  .enablePlugins(AshScriptPlugin)
  .dependsOn(keytool, shared, testShared % Test)
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

lazy val keytool = (project in file("modules/keytool"))
  .enablePlugins(AshScriptPlugin)
  .dependsOn(shared, testShared % Test)
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
      Libraries.brotli4j,
      Libraries.cats,
      Libraries.catsEffect,
      Libraries.chill,
      Libraries.circeCore,
      Libraries.circeGeneric,
      Libraries.circeGenericExtras,
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
      Libraries.drosteCore,
      Libraries.enumeratumCore,
      Libraries.enumeratumCirce,
      Libraries.fs2Core,
      Libraries.fs2DataCsv,
      Libraries.fs2DataCsvGeneric,
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
      Libraries.pureconfigCore,
      Libraries.pureconfigCats,
      Libraries.pureconfigEnumeratum,
      Libraries.pureconfigHttp4s,
      Libraries.pureconfigIp4s,
      Libraries.refinedPureconfig,
      Libraries.http4sCore,
      Libraries.http4sDsl,
      Libraries.http4sServer,
      Libraries.http4sClient,
      Libraries.http4sCirce
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

lazy val nodeShared = (project in file("modules/node-shared"))
  .dependsOn(shared % "compile->compile;test->test", testShared % Test, keytool, kernel)
  .configs(IntegrationTest)
  .settings(
    name := "tessellation-node-shared",
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
      Libraries.pureconfigCore,
      Libraries.pureconfigCats,
      Libraries.pureconfigCatsEffect,
      Libraries.pureconfigEnumeratum,
      Libraries.pureconfigHttp4s,
      Libraries.pureconfigIp4s,
      Libraries.refinedPureconfig,
      Libraries.shapeless
    )
  )

lazy val rosetta = (project in file("modules/rosetta"))
  .dependsOn(kernel, shared % "compile->compile;test->test", nodeShared, testShared % Test)
  .settings(
    name := "tessellation-rosetta",
    Defaults.itSettings,
    scalafixCommonSettings,
    commonSettings,
    commonTestSettings,
    makeBatScripts := Seq(),
    libraryDependencies ++= Seq(
      CompilerPlugin.kindProjector,
      CompilerPlugin.betterMonadicFor,
      CompilerPlugin.semanticDB,
      Libraries.cats,
      Libraries.catsEffect,
      Libraries.derevoCats,
      Libraries.derevoCirce,
      Libraries.derevoCore,
      Libraries.newtype,
      Libraries.refinedCore
    )
  )

lazy val dagL1 = (project in file("modules/dag-l1"))
  .enablePlugins(AshScriptPlugin)
  .enablePlugins(JavaAppPackaging)
  .dependsOn(kernel, shared % "compile->compile;test->test", nodeShared, testShared % Test)
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
      Libraries.log4cats,
      Libraries.logback % Runtime,
      Libraries.logstashLogbackEncoder % Runtime,
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
  .dependsOn(dagL0, dagL1)
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

lazy val dagL0 = (project in file("modules/dag-l0"))
  .enablePlugins(AshScriptPlugin)
  .enablePlugins(JavaAppPackaging)
  .dependsOn(keytool, kernel, shared % "compile->compile;test->test", testShared % Test, nodeShared)
  .settings(
    name := "tessellation-dag-l0",
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
      Libraries.doobieQuill,
      Libraries.drosteCore,
      Libraries.drosteLaws,
      Libraries.drosteMacros,
      Libraries.fs2Core,
      Libraries.flyway,
      Libraries.fs2DataCsv,
      Libraries.fs2DataCsvGeneric,
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

lazy val currencyL1 = (project in file("modules/currency-l1"))
  .dependsOn(dagL1, nodeShared, shared)
  .settings(
    name := "tessellation-currency-l1",
    Defaults.itSettings,
    scalafixCommonSettings,
    commonSettings,
    commonTestSettings,
    libraryDependencies ++= Seq(
      CompilerPlugin.kindProjector,
      CompilerPlugin.betterMonadicFor,
      CompilerPlugin.semanticDB
    )
  )

lazy val currencyL0 = (project in file("modules/currency-l0"))
  .enablePlugins(AshScriptPlugin)
  .enablePlugins(BuildInfoPlugin)
  .enablePlugins(JavaAppPackaging)
  .dependsOn(keytool, kernel, shared % "compile->compile;test->test", testShared % Test, nodeShared)
  .settings(
    name := "tessellation-currency-l0",
    buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion),
    buildInfoPackage := "org.tessellation.currency",
    Defaults.itSettings,
    scalafixCommonSettings,
    commonSettings,
    commonTestSettings,
    libraryDependencies ++= Seq(
      CompilerPlugin.kindProjector,
      CompilerPlugin.betterMonadicFor,
      CompilerPlugin.semanticDB
    )
  )

lazy val sdk = (project in file("modules/sdk"))
  .enablePlugins(AshScriptPlugin)
  .enablePlugins(BuildInfoPlugin)
  .enablePlugins(JavaAppPackaging)
  .dependsOn(keytool, kernel, shared % "compile->compile;test->test", testShared % Test, nodeShared)
  .settings(
    name := "tessellation-sdk",
    Defaults.itSettings,
    scalafixCommonSettings,
    commonSettings,
    commonTestSettings,
    libraryDependencies ++= Seq(
      CompilerPlugin.kindProjector,
      CompilerPlugin.betterMonadicFor,
      CompilerPlugin.semanticDB
    )
  )

addCommandAlias("runLinter", ";scalafixAll --rules OrganizeImports")
