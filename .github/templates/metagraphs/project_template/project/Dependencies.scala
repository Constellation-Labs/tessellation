import sbt.*

object Dependencies {

  object V {
    val tessellation: String = sys.env.getOrElse("TESSELLATION_VERSION", "99.99.99")
    val decline = "2.4.1"
    val organizeImports = "0.5.0"
    val requests = "0.8.0"
  }

  def tessellation(artifact: String): ModuleID = "io.constellationnetwork" %% s"tessellation-$artifact" % V.tessellation
  def decline(artifact: String = ""): ModuleID = "com.monovore" %% { if (artifact.isEmpty) "decline" else s"decline-$artifact" } % V.decline

  object Libraries {
    val tessellationSdk = tessellation("sdk")
    val declineCore = decline()
    val declineEffect = decline("effect")
    val declineRefined = decline("refined")
    val requests = "com.lihaoyi" %% "requests" % V.requests
    val organizeImports = "com.github.liancheng" %% "organize-imports" % V.organizeImports
  }

  // Scalafix rules
  val organizeImports = "com.github.liancheng" %% "organize-imports" % "0.5.0"

  object CompilerPlugin {

    val betterMonadicFor = compilerPlugin(
      "com.olegpy" %% "better-monadic-for" % "0.3.1"
    )

    val kindProjector = compilerPlugin(
      ("org.typelevel" % "kind-projector" % "0.13.3").cross(CrossVersion.full)
    )

    val semanticDB = compilerPlugin(
      ("org.scalameta" % "semanticdb-scalac" % "4.13.1.1").cross(CrossVersion.full)
    )
  }
}
