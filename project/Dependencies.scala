import sbt._

object Dependencies {

  object V {
    val bouncyCastle = "1.69"
    val cats = "2.6.1"
    val catsEffect = "3.2.9"
    val catsRetry = "2.1.0" // not published for CE3 yet
    val circe = "0.14.1"
    val ciris = "2.1.1"
    val comcast = "3.0.3"
    val decline = "2.2.0"
    val derevo = "0.12.6"
    val doobie = "1.0.0-RC1"
    val droste = "0.8.0"
    val enumeratum = "1.7.0"
    val h2 = "1.4.200"
    val javaxCrypto = "1.0.1"
    val flyway = "8.0.0"
    val fs2 = "3.1.1"
    val fs2Data = "1.1.0"
    val guava = "30.1.1-jre"
    val http4s = "0.23.10"
    val http4sJwtAuth = "1.0.0"
    val httpSigner = "0.4.3"
    val log4cats = "2.1.1"
    val micrometer = "1.7.4"
    val monocle = "3.0.0"
    val mapref = "0.2.0-M2"
    val newtype = "0.4.4"
    val refined = "0.9.28"
    val redis4cats = "1.0.0"
    val skunk = "0.2.1"
    val sqlite = "3.36.0.3"
    val chill = "0.9.5"
    val shapeless = "2.3.7"
    val spongyCastle = "1.58.0.0"
    val squants = "1.8.2"
    val twitterChill = "0.10.0"
    val betterMonadicFor = "0.3.1"
    val kindProjector = "0.13.2"
    val logback = "1.2.5"
    val organizeImports = "0.5.0"
    val semanticDB = "4.4.34"
    val weaver = "0.7.4"
  }

  object Libraries {
    def circe(artifact: String, version: String = V.circe): ModuleID = "io.circe" %% s"circe-$artifact" % version
    def ciris(artifact: String): ModuleID = "is.cir" %% artifact % V.ciris
    def derevo(artifact: String): ModuleID = "tf.tofu" %% s"derevo-$artifact" % V.derevo

    def enumeratum(artifact: String): ModuleID =
      "com.beachape" %% { if (artifact.isEmpty) "enumeratum" else s"enumeratum-$artifact" } % V.enumeratum

    def decline(artifact: String = ""): ModuleID =
      "com.monovore" %% { if (artifact.isEmpty) "decline" else s"decline-$artifact" } % V.decline

    def doobie(artifact: String): ModuleID =
      ("org.tpolecat" %% s"doobie-$artifact" % V.doobie).exclude("org.slf4j", "slf4j-api")
    def droste(artifact: String): ModuleID = "io.higherkindness" %% s"droste-$artifact" % V.droste
    def fs2(artifact: String): ModuleID = "co.fs2" %% s"fs2-$artifact" % V.fs2
    def fs2Data(artifact: String): ModuleID = "org.gnieh" %% s"fs2-data-$artifact" % V.fs2Data
    def http4s(artifact: String): ModuleID = "org.http4s" %% s"http4s-$artifact" % V.http4s
    def bouncyCastle(artifact: String): ModuleID = "org.bouncycastle" % artifact % V.bouncyCastle

    val bc = bouncyCastle("bcprov-jdk15on")
    val bcExtensions = bouncyCastle("bcpkix-jdk15on")

    val cats = "org.typelevel" %% "cats-core" % V.cats
    val catsEffect = "org.typelevel" %% "cats-effect" % V.catsEffect
    val catsRetry = "com.github.cb372" %% "cats-retry" % V.catsRetry

    val squants = "org.typelevel" %% "squants" % V.squants
    val comcast = "com.comcast" %% "ip4s-core" % V.comcast

    val fs2Core = fs2("core")
    val fs2DataCsv = fs2Data("csv")
    val fs2DataCsvGeneric = fs2Data("csv-generic")
    val fs2IO = fs2("io")

    val circeCore = circe("core")
    val circeGeneric = circe("generic")
    val circeParser = circe("parser")
    val circeRefined = circe("refined")
    val circeFs2 = circe("fs2", "0.14.0")

    val cirisCore = ciris("ciris")
    val cirisEnum = ciris("ciris-enumeratum")
    val cirisRefined = ciris("ciris-refined")

    val declineCore = decline()
    val declineEffect = decline("effect")
    val declineRefined = decline("refined")

    val derevoCore = derevo("core")
    val derevoCats = derevo("cats")
    val derevoScalacheck = derevo("scalacheck")
    val derevoCirce = derevo("circe-magnolia")

    val doobieCore = doobie("core")
    val doobieHikari = doobie("hikari")
    val doobieH2 = doobie("h2")
    val doobieQuill = doobie("quill")

    val drosteCore = droste("core")
    val drosteLaws = droste("laws")
    val drosteMacros = droste("macros")

    val enumeratumCore = enumeratum("")
    val enumeratumCirce = enumeratum("circe")

    val flyway = "org.flywaydb" % "flyway-core" % V.flyway

    val h2 = "com.h2database" % "h2" % V.h2

    val http4sCore = http4s("core")
    val http4sDsl = http4s("dsl")
    val http4sServer = http4s("ember-server")
    val http4sClient = http4s("ember-client")
    val http4sCirce = http4s("circe")

    val httpSignerCore = "pl.abankowski" %% "http-request-signer-core" % V.httpSigner
    val httpSignerHttp4s = "pl.abankowski" %% "http4s-request-signer" % V.httpSigner

    val guava = "com.google.guava" % "guava" % V.guava

    val http4sJwtAuth = "dev.profunktor" %% "http4s-jwt-auth" % V.http4sJwtAuth

    val monocleCore = "dev.optics" %% "monocle-core" % V.monocle
    val monocleMacro = "dev.optics" %% "monocle-macro" % V.monocle

    val micrometerPrometheusRegistry = "io.micrometer" % "micrometer-registry-prometheus" % V.micrometer

    val refinedCore = "eu.timepit" %% "refined" % V.refined
    val refinedCats = "eu.timepit" %% "refined-cats" % V.refined

    val log4cats = "org.typelevel" %% "log4cats-slf4j" % V.log4cats
    val newtype = "io.estatico" %% "newtype" % V.newtype

    val javaxCrypto = "javax.xml.crypto" % "jsr105-api" % V.javaxCrypto

    val redis4catsEffects = "dev.profunktor" %% "redis4cats-effects" % V.redis4cats
    val redis4catsLog4cats = "dev.profunktor" %% "redis4cats-log4cats" % V.redis4cats

    val skunkCore = "org.tpolecat" %% "skunk-core" % V.skunk
    val skunkCirce = "org.tpolecat" %% "skunk-circe" % V.skunk

    val sqlite = "org.xerial" % "sqlite-jdbc" % V.sqlite

    val chill = "com.twitter" %% "chill" % V.twitterChill
    val shapeless = "com.chuusai" %% "shapeless" % V.shapeless

    val mapref = "io.chrisdavenport" %% "mapref" % V.mapref

    // Runtime
    val logback = "ch.qos.logback" % "logback-classic" % V.logback

    // Test
    val catsLaws = "org.typelevel" %% "cats-laws" % V.cats
    val log4catsNoOp = "org.typelevel" %% "log4cats-noop" % V.log4cats
    val monocleLaw = "dev.optics" %% "monocle-law" % V.monocle
    val refinedScalacheck = "eu.timepit" %% "refined-scalacheck" % V.refined
    val weaverCats = "com.disneystreaming" %% "weaver-cats" % V.weaver
    val weaverDiscipline = "com.disneystreaming" %% "weaver-discipline" % V.weaver
    val weaverScalaCheck = "com.disneystreaming" %% "weaver-scalacheck" % V.weaver

    // Scalafix rules
    val organizeImports = "com.github.liancheng" %% "organize-imports" % V.organizeImports
  }

  object CompilerPlugin {

    val betterMonadicFor = compilerPlugin(
      "com.olegpy" %% "better-monadic-for" % V.betterMonadicFor
    )

    val kindProjector = compilerPlugin(
      ("org.typelevel" % "kind-projector" % V.kindProjector).cross(CrossVersion.full)
    )

    val semanticDB = compilerPlugin(
      ("org.scalameta" % "semanticdb-scalac" % V.semanticDB).cross(CrossVersion.full)
    )
  }

}
