package org.tessellation.cli

import scala.concurrent.duration.DurationInt

import org.tessellation.config.types.SnapshotConfig
import org.tessellation.ext.decline.decline._
import org.tessellation.sdk.config.types.ConsensusConfig

import com.monovore.decline._
import eu.timepit.refined.auto._
import fs2.io.file.Path

object snapshot {

  val globalSnapshotPath: Opts[Path] = Opts
    .env[Path]("CL_SNAPSHOT_STORED_PATH", help = "Path to store created snapshot")
    .withDefault(Path("data/snapshot"))

  val opts = globalSnapshotPath.map(SnapshotConfig(ConsensusConfig(43.seconds, 50.seconds, 10.seconds), _, 10L))
}
