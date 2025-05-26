package io.constellationnetwork.node.shared.domain.snapshot.storage

import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo}
import io.constellationnetwork.security.Hashed

trait LastSyncGlobalSnapshotStorage[F[_]] extends LastSnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo] {
  def getLastSynchronizedCombined: F[Option[(Hashed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)]]
}
