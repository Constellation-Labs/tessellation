package io.constellationnetwork.node.shared.domain.snapshot.storage

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.swap.AllowSpend
import io.constellationnetwork.schema.tokenLock.TokenLock
import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo, SnapshotOrdinal}
import io.constellationnetwork.security.signature.Signed

trait LastSyncGlobalSnapshotStorage[F[_]] extends LastSnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo] {

  /** Exact historical value/context lookup used by deterministic Currency binary construction.
    *
    * Implementations may satisfy this from the bounded in-memory window or the existing combined snapshot checkpoint on disk. The returned
    * snapshot is unsigned, so callers that rely on a signed `GlobalSyncView` must re-hash it with the hasher selected for this ordinal
    * before using its state.
    */
  def getCombined(ordinal: SnapshotOrdinal): F[Option[(GlobalIncrementalSnapshot, GlobalSnapshotInfo)]]
  def getLastSynchronizedCombined: F[Option[(GlobalIncrementalSnapshot, GlobalSnapshotInfo)]]
  def getLastSynchronized: F[Option[GlobalIncrementalSnapshot]]
  def getLastSynchronizedActiveAllowSpends: F[Option[SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]]]]
  def getLastSynchronizedActiveTokenLocks: F[Option[SortedMap[Address, SortedSet[Signed[TokenLock]]]]]
}
