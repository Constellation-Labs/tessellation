package org.tessellation.cli

import scala.concurrent.duration.DurationInt

import org.tessellation.config.types.{TrustConfig, TrustDaemonConfig}
import org.tessellation.ext.decline.decline._

import com.monovore.decline._
import fs2.io.file.Path

object trust {

  val trustPath: Opts[Path] = Opts
    .env[Path]("CL_TRUST_STORED_PATH", help = "Path to store trust related data")
    .withDefault(Path("tmp/trust"))

  val opts = trustPath.map(TrustConfig(TrustDaemonConfig(10.minutes), _))
}
