package org.tessellation.l0.cli

import scala.concurrent.duration.DurationInt

import org.tessellation.ext.decline.decline._
import org.tessellation.l0.config.SnapshotConfig

import com.monovore.decline._
import fs2.io.file.Path

object snapshot {

  val storedSnapshotPath: Opts[Path] = Opts
    .env[Path]("CL_SNAPSHOT_STORED_PATH", help = "Path to store created snapshot")
    .withDefault(Path("snapshot"))

  val opts = storedSnapshotPath.map(SnapshotConfig(_, 10.seconds))
}
