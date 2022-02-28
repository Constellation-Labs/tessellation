package org.tessellation.modules

import org.tessellation.config.types.SnapshotConfig
import org.tessellation.infrastructure.snapshot.SnapshotPreconditionsValidator

object Validators {

  def make[F[_]](snapshotConfig: SnapshotConfig) = {
    val snapshotPreconditions = new SnapshotPreconditionsValidator[F](snapshotConfig)

    new Validators[F](snapshotPreconditions) {}
  }
}

sealed abstract class Validators[F[_]] private (
  val snapshotPreconditions: SnapshotPreconditionsValidator[F]
)
