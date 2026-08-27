package io.constellationnetwork.node.shared.domain.snapshot.storage

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.swap.AllowSpend
import io.constellationnetwork.schema.tokenLock.TokenLock
import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo, SnapshotOrdinal}
import io.constellationnetwork.security.Hashed
import io.constellationnetwork.security.signature.Signed

trait LastSyncGlobalSnapshotStorage[F[_]] extends LastSnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo] {

  /** Return the exact historical Global snapshot context used by consensus-sensitive Currency L0 calculations. */
  def getCombined(ordinal: SnapshotOrdinal): F[Option[(GlobalIncrementalSnapshot, GlobalSnapshotInfo)]]

  def getLastSynchronizedCombined: F[Option[(GlobalIncrementalSnapshot, GlobalSnapshotInfo)]]
  def getLastSynchronized: F[Option[GlobalIncrementalSnapshot]]
  def getLastSynchronizedActiveAllowSpends: F[Option[SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]]]]
  def getLastSynchronizedActiveTokenLocks: F[Option[SortedMap[Address, SortedSet[Signed[TokenLock]]]]]
}
