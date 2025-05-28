import sbt._

object Dependencies {

  object V {
    val bouncyCastle = "1.70"
    val betterFiles = "3.9.2"
    val brotli4j = "1.12.0"
    val cats = "2.9.0"
    val catsEffect = "3.4.2"
    val catsRetry = "3.1.0"
    val circe = "0.14.3"
    val ciris = "3.0.0"
    val comcast = "3.2.0"
    val decline = "2.4.1"
    val derevo = "0.13.0"
    val doobie = "1.0.0-RC1"
    val droste = "0.9.0"
    val enumeratum = "1.7.2"
    val javaxCrypto = "1.0.1"
    val jawnVersion = "1.4.0"
    val jawnFs2Version = "2.4.0"
    val flyway = "9.10.2"
    val fs2 = "3.4.0"
    val fs2Data = "1.6.0"
    val guava = "31.1-jre"
    val http4s = "0.23.16"
    val http4sJwtAuth = "1.0.0"
    val httpSigner = "0.1.0"
    val log4cats = "2.5.0"
    val micrometer = "1.10.2"
    val monocle = "3.1.0"
    val mapref = "0.2.0-M2"
    val newtype = "0.4.4"
    val pureconfig = "0.17.5"
    val refined = "0.10.1"
    val redis4cats = "1.3.0"
    val skunk = "0.3.2"
    val sqlite = "3.40.0.0"
    val chill = "0.9.5"
    val shapeless = "2.3.10"
    val spongyCastle = "1.58.0.0"
    val squants = "1.8.3"
    val twitterChill = "0.10.0"
    val betterMonadicFor = "0.3.1"
    val kindProjector = "0.13.3"
    val logback = "1.3.5"
    val logstashLogbackEncoder = "7.2"
    val organizeImports = "0.5.0"
    val semanticDB = "4.13.1.1"
    val weaver = "0.8.1"
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

    def jawn(artifact: String): ModuleID = "org.typelevel" %% artifact % V.jawnVersion

    def pureconfig(artifact: String): ModuleID = "com.github.pureconfig" %% s"pureconfig-$artifact" % V.pureconfig

    val bc = bouncyCastle("bcprov-jdk15on")
    val bcExtensions = bouncyCastle("bcpkix-jdk15on")

    val betterFiles = "com.github.pathikrit" %% "better-files" % V.betterFiles

    val brotli4j = "com.aayushatharva.brotli4j" % "brotli4j" % V.brotli4j
    def brotli4jNative(artifact: String): ModuleID = brotli4j.withName(s"native-$artifact")

    val cats = "org.typelevel" %% "cats-core" % V.cats
    val catsEffect = "org.typelevel" %% "cats-effect" % V.catsEffect
    val catsEffectTestkit = "org.typelevel" %% "cats-effect-testkit" % V.catsEffect
    val catsRetry = "com.github.cb372" %% "cats-retry" % V.catsRetry

    val squants = "org.typelevel" %% "squants" % V.squants
    val comcast = "com.comcast" %% "ip4s-core" % V.comcast

    val fs2Core = fs2("core")
    val fs2DataCsv = fs2Data("csv")
    val fs2DataCsvGeneric = fs2Data("csv-generic")
    val fs2IO = fs2("io")

    val circeCore = circe("core")
    val circeGeneric = circe("generic")
    val circeGenericExtras = circe("generic-extras")
    val circeParser = circe("parser")
    val circeRefined = circe("refined")
    val circeFs2 = circe("fs2", "0.14.0")
    val circeShapes = circe("shapes")

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
    val doobieQuill = doobie("quill")

    val drosteCore = droste("core")
    val drosteLaws = droste("laws")
    val drosteMacros = droste("macros")

    val enumeratumCore = enumeratum("")
    val enumeratumCirce = enumeratum("circe")

    val flyway = "org.flywaydb" % "flyway-core" % V.flyway

    val http4sCore = http4s("core")
    val http4sDsl = http4s("dsl")
    val http4sServer = http4s("ember-server")
    val http4sClient = http4s("ember-client")
    val http4sCirce = http4s("circe")

    val httpSignerCore = "io.constellationnetwork" %% "http-request-signer-core" % V.httpSigner
    val httpSignerHttp4s = "io.constellationnetwork" %% "http4s-request-signer" % V.httpSigner

    val guava = "com.google.guava" % "guava" % V.guava

    val http4sJwtAuth = "dev.profunktor" %% "http4s-jwt-auth" % V.http4sJwtAuth

    val jawnParser = jawn("jawn-parser")
    val jawnAst = jawn("jawn-ast")
    val jawnFs2 = "org.typelevel" %% "jawn-fs2" % V.jawnFs2Version

    val monocleCore = "dev.optics" %% "monocle-core" % V.monocle
    val monocleMacro = "dev.optics" %% "monocle-macro" % V.monocle

    val micrometerPrometheusRegistry = "io.micrometer" % "micrometer-registry-prometheus" % V.micrometer

    val refinedCore = "eu.timepit" %% "refined" % V.refined
    val refinedCats = "eu.timepit" %% "refined-cats" % V.refined
    val refinedPureconfig = "eu.timepit" %% "refined-pureconfig" % V.refined

    val log4cats = "org.typelevel" %% "log4cats-slf4j" % V.log4cats
    val newtype = "io.estatico" %% "newtype" % V.newtype

    val redis4catsEffects = "dev.profunktor" %% "redis4cats-effects" % V.redis4cats
    val redis4catsLog4cats = "dev.profunktor" %% "redis4cats-log4cats" % V.redis4cats

    val skunkCore = "org.tpolecat" %% "skunk-core" % V.skunk
    val skunkCirce = "org.tpolecat" %% "skunk-circe" % V.skunk

    val sqlite = "org.xerial" % "sqlite-jdbc" % V.sqlite

    val chill = "com.twitter" %% "chill" % V.twitterChill
    val shapeless = "com.chuusai" %% "shapeless" % V.shapeless

    val mapref = "io.chrisdavenport" %% "mapref" % V.mapref

    val pureconfigCore = "com.github.pureconfig" %% "pureconfig" % V.pureconfig
    val pureconfigCats = pureconfig("cats")
    val pureconfigCatsEffect = pureconfig("cats-effect")
    val pureconfigEnumeratum = pureconfig("enumeratum")
    val pureconfigHttp4s = pureconfig("http4s")
    val pureconfigIp4s = pureconfig("ip4s")

    // Runtime
    val logback = "ch.qos.logback" % "logback-classic" % V.logback
    val logstashLogbackEncoder = "net.logstash.logback" % "logstash-logback-encoder" % V.logstashLogbackEncoder

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
