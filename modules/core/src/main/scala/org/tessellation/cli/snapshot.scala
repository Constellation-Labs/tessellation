package org.tessellation.cli

import scala.concurrent.duration.DurationInt

import org.tessellation.config.types.SnapshotConfig
import org.tessellation.ext.decline.decline._
import org.tessellation.sdk.config.types.{ConsensusConfig, ObservationConfig}

import com.monovore.decline._
import eu.timepit.refined.auto._
import fs2.io.file.Path

object snapshot {

  val globalSnapshotPath: Opts[Path] = Opts
    .env[Path]("CL_SNAPSHOT_STORED_PATH", help = "Path to store created snapshot")
    .withDefault(Path("data/snapshot"))

  val opts = globalSnapshotPath.map { globalSnapshotPath =>
    SnapshotConfig(
      consensus = ConsensusConfig(
        timeTriggerInterval = 43.seconds,
        declarationTimeout = 50.seconds,
        lockDuration = 10.seconds,
        observation = ObservationConfig(
          interval = 10.seconds,
          timeout = 10.minutes,
          offset = 3L
        )
      ),
      globalSnapshotPath = globalSnapshotPath,
      inMemoryCapacity = 10L
    )
  }
}
