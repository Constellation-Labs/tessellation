package io.constellationnetwork.node.shared.domain.snapshot.storage

import io.constellationnetwork.node.shared.domain.snapshot.services.GlobalL0Service
import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo, SnapshotOrdinal}
import io.constellationnetwork.security.Hashed
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed

trait LastNGlobalSnapshotStorage[F[_]] extends LastSnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo] {
  type GlobalFetcher = Either[GlobalL0Service[F], SnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo]]
  type FetchFunction = (Option[Hash], SnapshotOrdinal) => F[Signed[GlobalIncrementalSnapshot]]

  def getLastN: F[List[Hashed[GlobalIncrementalSnapshot]]]
  def setInitialFetchingGL0(
    snapshot: Hashed[GlobalIncrementalSnapshot],
    state: GlobalSnapshotInfo,
    globalSnapshotFetcher: Option[GlobalFetcher],
    fetchGL0Function: Option[FetchFunction]
  ): F[Unit]
}
