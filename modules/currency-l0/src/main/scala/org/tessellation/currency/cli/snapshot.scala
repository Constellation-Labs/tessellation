package org.tessellation.currency.cli

import com.monovore.decline.Opts
import fs2.io.file.Path
import org.tessellation.currency.config.types.SnapshotConfig
import org.tessellation.ext.decline.decline._
import com.monovore.decline._
import eu.timepit.refined.auto._

object snapshot {

  val snapshotPath: Opts[Path] = Opts
    .env[Path]("CL_SNAPSHOT_STORED_PATH", help = "Path to store created snapshot")
    .withDefault(Path("data/snapshot"))

  val opts: Opts[SnapshotConfig] = snapshotPath.map(SnapshotConfig(2L, _, 10L))
}
