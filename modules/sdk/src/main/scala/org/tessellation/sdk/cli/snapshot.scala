package org.tessellation.sdk.cli

import cats.syntax.contravariantSemigroupal._
import cats.syntax.eq._
import cats.syntax.validated._

import scala.concurrent.duration.DurationInt

import org.tessellation.ext.decline.decline._
import org.tessellation.sdk.config.types.{ConsensusConfig, SnapshotConfig}

import com.monovore.decline._
import eu.timepit.refined.auto._
import fs2.io.file.Path

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

  val opts = (snapshotPath, incrementalPersistedSnapshotPath, incrementalTmpSnapshotPath).tupled.mapValidated {
    case (snapshotPath, incrementalPersistedSnapshotPath, incrementalTmpSnapshotPath)
        if snapshotPath =!= incrementalPersistedSnapshotPath && incrementalPersistedSnapshotPath =!= incrementalTmpSnapshotPath =>
      (snapshotPath, incrementalPersistedSnapshotPath, incrementalTmpSnapshotPath).validNel[String]
    case _ =>
      "Paths for global snapshot and incremental snapshot (both persisted and tmp) must be different.".invalidNel[(Path, Path, Path)]
  }.map {
    case (snapshotPath, incrementalPersistedSnapshotPath, incrementalTmpSnapshotPath) =>
      SnapshotConfig(
        consensus = ConsensusConfig(
          timeTriggerInterval = 43.seconds,
          declarationTimeout = 50.seconds,
          declarationRangeLimit = 3L,
          lockDuration = 10.seconds
        ),
        snapshotPath = snapshotPath,
        incrementalTmpSnapshotPath = incrementalTmpSnapshotPath,
        incrementalPersistedSnapshotPath = incrementalPersistedSnapshotPath,
        inMemoryCapacity = 10L
      )
  }
}
