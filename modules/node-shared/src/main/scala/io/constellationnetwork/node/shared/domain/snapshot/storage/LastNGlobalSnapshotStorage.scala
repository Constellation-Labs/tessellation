package io.constellationnetwork.node.shared.domain.snapshot.storage

import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo, SnapshotOrdinal}
import io.constellationnetwork.security.Hashed

trait LastNGlobalSnapshotStorage[F[_]] extends LastSnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo] {
  def getLastN(ordinal: SnapshotOrdinal, n: Int): F[Option[List[Hashed[GlobalIncrementalSnapshot]]]]
}
