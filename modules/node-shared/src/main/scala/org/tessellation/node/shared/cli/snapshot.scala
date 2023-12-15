package org.tessellation.node.shared.cli

import cats.syntax.contravariantSemigroupal._
import cats.syntax.eq._
import cats.syntax.validated._

import scala.concurrent.duration.DurationInt

import org.tessellation.ext.decline.decline._
import org.tessellation.node.shared.config.types.{ConsensusConfig, EventCutterConfig, SnapshotConfig}

import com.monovore.decline._
import eu.timepit.refined.auto._
import fs2.io.file.Path
import shapeless.syntax.std.tuple._

object snapshot {

  val snapshotPath: Opts[Path] = Opts
    .env[Path]("CL_SNAPSHOT_STORED_PATH", help = "Path to store created snapshot")
    .withDefault(Path("data/snapshot"))

  val incrementalPersistedSnapshotPath: Opts[Path] = Opts
    .env[Path]("CL_INCREMENTAL_SNAPSHOT_STORED_PATH", help = "Path to store created incremental snapshot")
    .withDefault(Path("data/incremental_snapshot"))

  val incrementalTmpSnapshotPath: Opts[Path] = Opts
    .env[Path]("CL_INCREMENTAL_SNAPSHOT_TMP_STORED_PATH", help = "Path to tmp storage of incremental snapshot")
    .withDefault(Path("data/incremental_snapshot_tmp"))

  val snapshotInfoPath: Opts[Path] = Opts
    .env[Path]("CL_SNAPSHOT_INFO_PATH", help = "Path to store snapshot infos")
    .withDefault(Path("data/snapshot_info"))

  val pathsOpts = (snapshotPath, incrementalPersistedSnapshotPath, incrementalTmpSnapshotPath, snapshotInfoPath)

  val opts = pathsOpts.tupled.mapValidated {
    case tuple if tuple.toList.size === tuple.toList.toSet.size => tuple.validNel[String]
    case _ => "Paths for file storages must differ.".invalidNel[(Path, Path, Path, Path)]
  }.map {
    case (snapshotPath, incrementalPersistedSnapshotPath, incrementalTmpSnapshotPath, snapshotInfoPath) =>
      SnapshotConfig(
        consensus = ConsensusConfig(
          timeTriggerInterval = 43.seconds,
          declarationTimeout = 50.seconds,
          declarationRangeLimit = 3L,
          lockDuration = 10.seconds,
          EventCutterConfig(maxBinarySizeBytes = 20_971_520)
        ),
        snapshotPath = snapshotPath,
        incrementalTmpSnapshotPath = incrementalTmpSnapshotPath,
        incrementalPersistedSnapshotPath = incrementalPersistedSnapshotPath,
        inMemoryCapacity = 10L,
        snapshotInfoPath = snapshotInfoPath
      )
  }
}
