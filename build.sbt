import Dependencies._

ThisBuild / scalaVersion := "2.13.18"
ThisBuild / organization := "io.constellationnetwork"
ThisBuild / organizationName := "constellationnetwork"

ThisBuild / homepage := Some(url("https://github.com/Constellation-Labs/tessellation"))
ThisBuild / licenses := List("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0"))
ThisBuild / sonatypeCredentialHost := "central.sonatype.com"
ThisBuild / developers := List(
  Developer(
    "tessellation-contributors",
    "Tessellation Contributors",
    "contact@constellationnetwork.io",
    url("https://github.com/Constellation-Labs/tessellation/graphs/contributors")
  )
)

ThisBuild / evictionErrorLevel := Level.Warn
ThisBuild / scalafixDependencies += Libraries.scalafixRules

// ===== Unified Versioning System =====
// See VERSIONING.md for complete documentation
//
// Version priority:
// 1. RELEASE_TAG env var (explicit override for releases)
// 2. Git tag (if HEAD is tagged)
// 3. Auto-generated from git state with metadata
//
// Examples:
//   Tagged v4.1.0           → 4.1.0
//   Tagged v4.1.0-rc.1      → 4.1.0-rc.1
//   3 commits after tag     → 4.1.0+3.abc1234.build42
//   Local dev               → 4.1.0+3.abc1234.local
ThisBuild / version := {
  // Priority 1: Explicit RELEASE_TAG override
  sys.env.get("RELEASE_TAG").filter(_.nonEmpty).map(_.stripPrefix("v")).getOrElse {
    // Priority 2 & 3: Derive from git via sbt-dynver
    val dynverOutput = dynverGitDescribeOutput.value
    val buildId = sys.env.get("GITHUB_RUN_NUMBER").map(n => s"build$n").getOrElse("local")
    
    dynverOutput match {
      case Some(out) if out.hasNoTags =>
        // Repository has no version tags - use fallback
        val sha = out.commitSuffix.sha
        s"0.0.0+notags.$sha.$buildId"
        
      case Some(out) =>
        val baseVersion = out.ref.dropPrefix
        val distance = out.commitSuffix.distance
        val sha = out.commitSuffix.sha
        val isDirty = out.isDirty()
        
        if (distance == 0 && !isDirty) {
          // Exactly on a tag with clean working directory
          baseVersion
        } else if (distance == 0 && isDirty) {
          // On a tag but with local changes
          s"$baseVersion+dirty.$buildId"
        } else {
          // Commits after tag (most common dev scenario)
          s"$baseVersion+$distance.$sha.$buildId"
        }
        
      case None =>
        // No git info available (shallow clone, no tags, etc.)
        val fallbackBase = "0.0.0"
        s"$fallbackBase+unknown.$buildId"
    }
  }
}

// Disable dynver's Sonatype snapshot suffix (we use +metadata format for dev versions)
ThisBuild / dynverSonatypeSnapshots := false

// Explicitly derive isSnapshot from git state so ci-release behaves correctly
// in any context (tag push, branch push, or local invocation)
ThisBuild / isSnapshot := {
  dynverGitDescribeOutput.value.forall(out =>
    out.commitSuffix.distance > 0 || out.isDirty()
  )
}

// Sonatype Central publishing — required by sbt-ci-release (+publishSigned)
// Modules with `publish / skip := true` are unaffected; only `sdk` publishes
ThisBuild / publishTo := sonatypePublishToBundle.value

// ===== Java 21 Migration Configuration =====

// Enforce Java 21 requirement at build time
// Java 21 is an LTS release with better performance, Virtual Threads, and improved GC
// This prevents "unsupported class version" errors if developers use wrong Java version
initialize := {
  val _ = initialize.value
  val required = "21"
  val current = sys.props("java.specification.version")
  assert(current == required, s"Java $required required. Current: $current")
}

// Set Java compiler to use version 21 for source and bytecode target
ThisBuild / javacOptions ++= Seq("-source", "21", "-target", "21")

// Configure Scala compiler to generate Java 21 compatible bytecode
ThisBuild / scalacOptions ++= Seq("-release", "21")

// sbt-ci-release auto-enables via AutoPlugin

val scalafixCommonSettings = inConfig(IntegrationTest)(scalafixConfigSettings(IntegrationTest))

bloopExportJarClassifiers in Global := Some(Set("sources"))

lazy val commonSettings = Seq(
  scalacOptions ++= List("-Ymacro-annotations", "-Yrangepos", "-Wconf:cat=unused:info", "-language:reflectiveCalls"),
  // On-compile rewrites are a local dev convenience only. In CI they silently repair
  // the workspace before *Check tasks run (making them pass vacuously) and dirty the
  // git tree, which shifts the dynver-derived version mid-publish.
  scalafmtOnCompile := !sys.env.contains("CI"),
  scalafixOnCompile := !sys.env.contains("CI"),
  resolvers ++= List(
    Resolver.sonatypeRepo("snapshots")
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
  case "logback.xml"                                             => MergeStrategy.first
  case x if x.contains("io.netty.versions.properties")           => MergeStrategy.discard
  case x if x.contains("scala.semanticdb")                       => MergeStrategy.discard
  case PathList("META-INF", "versions", _, "OSGI-INF", _ @_*)    => MergeStrategy.discard
  case PathList(xs @ _*) if xs.last == "module-info.class"       => MergeStrategy.first
  case x =>
    val oldStrategy = (assembly / assemblyMergeStrategy).value
    oldStrategy(x)
}

sdk / assemblyMergeStrategy := {
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
    name := "tessellation",
    publish / skip := true
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
    publish / skip := true,
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
    publish / skip := true,
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
    publish / skip := true,
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
    buildInfoPackage := "io.constellationnetwork",
    Defaults.itSettings,
    scalafixCommonSettings,
    commonSettings,
    commonTestSettings,
    publish / skip := true,
    makeBatScripts := Seq(),
    libraryDependencies ++= Seq(
      CompilerPlugin.kindProjector,
      CompilerPlugin.betterMonadicFor,
      CompilerPlugin.semanticDB,
      Libraries.bc,
      Libraries.bcExtensions,
      Libraries.betterFiles,
      Libraries.brotli4j,
      Libraries.brotli4jNative("linux-x86_64"),
      Libraries.brotli4jNative("linux-aarch64"),
      Libraries.brotli4jNative("osx-x86_64"),
      Libraries.brotli4jNative("osx-aarch64"),
      Libraries.cats,
      Libraries.catsEffect,
      Libraries.chill,
      Libraries.circeCore,
      Libraries.circeGeneric,
      Libraries.circeGenericExtras,
      Libraries.circeParser,
      Libraries.circeRefined,
      Libraries.circeShapes,
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
      Libraries.drosteCore,
      Libraries.enumeratumCore,
      Libraries.enumeratumCirce,
      Libraries.fs2Core,
      Libraries.fs2DataCsv,
      Libraries.fs2DataCsvGeneric,
      Libraries.log4cats,
      Libraries.logback % Runtime,
      Libraries.logstashLogbackEncoder % Runtime,
      Libraries.mapref,
      Libraries.scaffeine,
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
      Libraries.http4sCirce,
      Libraries.circeFs2,
      Libraries.clickHouse,
      Libraries.hikari
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
    publish / skip := true,
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
    publish / skip := true,
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
      Libraries.shapeless,
      Libraries.jol
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
    publish / skip := true,
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
    publish / skip := true,
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
      Libraries.drosteCore,
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
    publish / skip := true,
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
      Libraries.refinedCats
    )
  )

lazy val dagL0 = (project in file("modules/dag-l0"))
  .enablePlugins(AshScriptPlugin)
  .enablePlugins(JavaAppPackaging)
  .dependsOn(keytool, kernel, shared % "compile->compile;test->test", testShared % Test, nodeShared % "compile->compile;test->test")
  .settings(
    name := "tessellation-dag-l0",
    Defaults.itSettings,
    scalafixCommonSettings,
    commonSettings,
    commonTestSettings,
    dockerSettings,
    publish / skip := true,
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
      Libraries.drosteCore,
      Libraries.drosteLaws,
      Libraries.drosteMacros,
      Libraries.fs2Core,
      Libraries.fs2DataCsv,
      Libraries.fs2DataCsvGeneric,
      Libraries.http4sDsl,
      Libraries.http4sServer,
      Libraries.http4sClient,
      Libraries.http4sCirce,
      Libraries.httpSignerCore,
      Libraries.httpSignerHttp4s,
      Libraries.log4cats,
      Libraries.logback % Runtime,
      Libraries.logstashLogbackEncoder % Runtime,
      Libraries.mapref,
      Libraries.monocleCore,
      Libraries.newtype,
      Libraries.refinedCore,
      Libraries.refinedCats
    )
  )

lazy val currencyL1 = (project in file("modules/currency-l1"))
  .dependsOn(dagL1, shared % "compile->compile;test->test", testShared % Test, nodeShared)
  .settings(
    name := "tessellation-currency-l1",
    Defaults.itSettings,
    scalafixCommonSettings,
    commonSettings,
    commonTestSettings,
    publish / skip := true,
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
  .dependsOn(keytool, kernel, shared % "compile->compile;test->test", testShared % Test, nodeShared % "compile->compile;test->test")
  .settings(
    name := "tessellation-currency-l0",
    buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion),
    buildInfoPackage := "io.constellationnetwork.currency",
    Defaults.itSettings,
    scalafixCommonSettings,
    commonSettings,
    commonTestSettings,
    publish / skip := true,
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
  .dependsOn(
    keytool % "provided",
    kernel % "provided",
    shared % "provided",
    nodeShared % "provided",
    currencyL0 % "provided",
    currencyL1 % "provided",
    dagL1 % "provided"
  )
  .settings(
    name := "tessellation-sdk",
    Defaults.itSettings,
    scalafixCommonSettings,
    commonSettings,
    commonTestSettings,
    publishArtifact in Test := false,
    pomIncludeRepository := { _ => false },
    Compile / publishMavenStyle := true,
    libraryDependencies ++= Seq(
      CompilerPlugin.kindProjector,
      CompilerPlugin.betterMonadicFor,
      CompilerPlugin.semanticDB,
    ) ++ Seq(
      (keytool / Compile / libraryDependencies).value,
      (kernel / Compile / libraryDependencies).value,
      (shared / Compile / libraryDependencies).value,
      (nodeShared / Compile / libraryDependencies).value,
      (currencyL0 / Compile / libraryDependencies).value,
      (currencyL1 / Compile / libraryDependencies).value,
      (dagL1 / Compile / libraryDependencies).value
    ).flatten,
    Compile / packageBin / mappings ++= Seq(
        (keytool / Compile / packageBin / mappings).value,
        (kernel / Compile / packageBin / mappings).value,
        (shared / Compile / packageBin / mappings).value,
        (nodeShared / Compile / packageBin / mappings).value,
        (currencyL0 / Compile / packageBin / mappings).value,
        (currencyL1 / Compile / packageBin / mappings).value,
        (dagL1 / Compile / packageBin / mappings).value
      ).flatten.filterNot { case (_, path) => path.endsWith("rally-version.properties")},
    Compile / packageSrc / mappings ++= Seq(
      (keytool / Compile / packageSrc / mappings).value,
      (kernel / Compile / packageSrc / mappings).value,
      (shared / Compile / packageSrc / mappings).value,
      (nodeShared / Compile / packageSrc / mappings).value,
      (currencyL0 / Compile / packageSrc / mappings).value,
      (currencyL1 / Compile / packageSrc / mappings).value,
      (dagL1 / Compile / packageSrc / mappings).value
    ).flatten,
    Compile / doc / sources ++= Seq(
      (keytool / Compile / doc / sources).value,
      (kernel / Compile / doc / sources).value,
      (shared / Compile / doc / sources).value,
      (nodeShared / Compile / doc / sources).value,
      (currencyL0 / Compile / doc / sources).value,
      (currencyL1 / Compile / doc / sources).value,
      (dagL1 / Compile / doc / sources).value
    ).flatten,
  )

addCommandAlias("runLinter", ";scalafixAll --rules OrganizeImports;scalafmtAll")
