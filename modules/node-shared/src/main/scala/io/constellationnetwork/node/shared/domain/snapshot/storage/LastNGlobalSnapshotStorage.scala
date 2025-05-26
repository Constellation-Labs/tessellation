package io.constellationnetwork.node.shared.domain.snapshot.storage

import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo, SnapshotOrdinal}
import io.constellationnetwork.security.Hashed
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed

trait LastNGlobalSnapshotStorage[F[_]] extends LastSnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo] {
  def getLastN: F[List[Hashed[GlobalIncrementalSnapshot]]]
  def setInitialFetchingGL0(
    snapshot: Hashed[GlobalIncrementalSnapshot],
    state: GlobalSnapshotInfo,
    fetchGL0Function: (Option[Hash], SnapshotOrdinal) => F[Signed[GlobalIncrementalSnapshot]]
  ): F[Unit]
}
