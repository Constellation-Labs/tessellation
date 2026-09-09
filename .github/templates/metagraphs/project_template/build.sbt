import Dependencies._
import sbt._

ThisBuild / organization := "com.my.project_template"
ThisBuild / scalaVersion := "2.13.16"
ThisBuild / evictionErrorLevel := Level.Warn
ThisBuild / scalafixDependencies += Libraries.organizeImports

ThisBuild / assemblyMergeStrategy := {
  case "logback.xml" => MergeStrategy.first
  case x if x.contains("io.netty.versions.properties") => MergeStrategy.discard
  case PathList("META-INF", "versions", _, "OSGI-INF", _ @_*)    => MergeStrategy.discard
  case PathList(xs@_*) if xs.last == "module-info.class" => MergeStrategy.first
  case x if x.contains("rally-version.properties") => MergeStrategy.concat
  case x =>
    val oldStrategy = (assembly / assemblyMergeStrategy).value
    oldStrategy(x)
}

lazy val commonSettings = Seq(
  testFrameworks += new TestFramework("weaver.framework.CatsEffect"),
  scalafmtOnCompile := true,
  scalafixOnCompile := true,
  scalacOptions ++= List("-Ymacro-annotations", "-Yrangepos", "-Wconf:cat=unused:info", "-language:reflectiveCalls"),
  buildInfoKeys := Seq[BuildInfoKey](
    name,
    version,
    scalaVersion,
    sbtVersion,
    // The SDK release identity is also the default CI metagraph identity. This keeps
    // independently rebuilt old/new L0 and L1 jars from silently advertising the same
    // hard-coded 1.0.0 join version. Real metagraph releases may override their own
    // coordinated version with METAGRAPH_VERSION.
    BuildInfoKey.action("tessellationVersion")(sys.env.getOrElse("TESSELLATION_VERSION", "99.99.99-SNAPSHOT")),
    BuildInfoKey.action("metagraphVersion")(
      sys.env.getOrElse("METAGRAPH_VERSION", sys.env.getOrElse("TESSELLATION_VERSION", "99.99.99-SNAPSHOT"))
    )
  ),
  resolvers ++= Seq(Resolver.mavenLocal),
  libraryDependencies ++= Seq(
    CompilerPlugin.kindProjector,
    CompilerPlugin.betterMonadicFor,
    CompilerPlugin.semanticDB
  )
)

lazy val root = (project in file(".")).
  settings(
    name := "project_template"
  ).aggregate(sharedData, currencyL0, currencyL1, dataL1)

lazy val sharedData = (project in file("modules/shared_data"))
  .enablePlugins(AshScriptPlugin, BuildInfoPlugin, JavaAppPackaging)
  .settings(
    name := "project_template-shared_data",
    buildInfoPackage := "com.my.project_template.shared_data",
    Defaults.itSettings,
    commonSettings,
    libraryDependencies ++= Seq(
      Libraries.tessellationSdk,
      Libraries.requests
    )
  )

lazy val currencyL1 = (project in file("modules/l1"))
  .enablePlugins(AshScriptPlugin, BuildInfoPlugin, JavaAppPackaging)
  .dependsOn(sharedData)
  .settings(
    name := "project_template-currency-l1",
    buildInfoPackage := "com.my.project_template.l1",
    Defaults.itSettings,
    commonSettings
  )

lazy val currencyL0 = (project in file("modules/l0"))
  .enablePlugins(AshScriptPlugin, BuildInfoPlugin, JavaAppPackaging)
  .dependsOn(sharedData)
  .settings(
    name := "project_template-currency-l0",
    buildInfoPackage := "com.my.project_template.l0",
    Defaults.itSettings,
    commonSettings,
    libraryDependencies ++= Seq(
      Libraries.declineRefined,
      Libraries.declineCore,
      Libraries.declineEffect
    )
  )

lazy val dataL1 = (project in file("modules/data_l1"))
  .enablePlugins(AshScriptPlugin, BuildInfoPlugin, JavaAppPackaging)
  .dependsOn(sharedData)
  .settings(
    name := "project_template-data_l1",
    buildInfoPackage := "com.my.project_template.data_l1",
    Defaults.itSettings,
    commonSettings
  )
