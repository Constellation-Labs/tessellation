package org.tessellation.cli

import org.tessellation.config.types.SnapshotConfig
import org.tessellation.ext.decline.decline._

import com.monovore.decline._
import fs2.io.file.Path

object snapshot {

  val storedSnapshotPath: Opts[Path] = Opts
    .env[Path]("CL_SNAPSHOT_STORED_PATH", help = "Path to store created snapshot")
    .withDefault(Path("snapshot"))

  val opts = storedSnapshotPath.map(SnapshotConfig)
}
