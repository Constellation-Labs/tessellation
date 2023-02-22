package org.tessellation.currency.cli

import scala.concurrent.duration.DurationInt

import org.tessellation.ext.decline.decline._
import org.tessellation.sdk.config.types.{ConsensusConfig, ObservationConfig, SnapshotConfig}

import com.monovore.decline._
import eu.timepit.refined.auto._
import fs2.io.file.Path

object snapshot {

  val snapshotPath: Opts[Path] = Opts
    .env[Path]("CL_SNAPSHOT_STORED_PATH", help = "Path to store created snapshot")
    .withDefault(Path("data/snapshot"))

  val opts = snapshotPath.map { snapshotPath =>
    SnapshotConfig(
      consensus = ConsensusConfig(
        timeTriggerInterval = 43.seconds,
        declarationTimeout = 50.seconds,
        declarationRangeLimit = 3L,
        lockDuration = 10.seconds,
        observation = ObservationConfig(
          interval = 10.seconds,
          timeout = 10.minutes,
          offset = 3L
        )
      ),
      snapshotPath = snapshotPath,
      inMemoryCapacity = 10L
    )
  }
}
