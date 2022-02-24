package org.tessellation.cli

import scala.concurrent.duration.DurationInt

import org.tessellation.config.types.SnapshotConfig
import org.tessellation.ext.decline.decline._

import com.monovore.decline._
import eu.timepit.refined.types.numeric.NonNegLong
import fs2.io.file.Path

object snapshot {

  val globalSnapshotPath: Opts[Path] = Opts
    .env[Path]("CL_SNAPSHOT_STORED_PATH", help = "Path to store created snapshot")
    .withDefault(Path("tmp/snapshot"))

  val opts = globalSnapshotPath.map(SnapshotConfig(_, 10.seconds, NonNegLong(100)))
}
