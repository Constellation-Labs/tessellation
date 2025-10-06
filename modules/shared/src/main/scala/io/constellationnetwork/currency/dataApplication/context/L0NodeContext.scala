package io.constellationnetwork.currency.dataApplication.context

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencySnapshotInfo}
import io.constellationnetwork.domain.seedlist.SeedlistEntry
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.swap.{AllowSpend, CurrencyId}
import io.constellationnetwork.schema.tokenLock.TokenLock
import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo, SnapshotOrdinal}
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed

trait L0NodeContext[F[_]] {
  def getLastSynchronizedGlobalSnapshot: F[Option[GlobalIncrementalSnapshot]]
  def getLastSynchronizedGlobalSnapshotCombined: F[Option[(GlobalIncrementalSnapshot, GlobalSnapshotInfo)]]
  def getLastSynchronizedAllowSpends: F[Option[SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]]]]
  def getLastSynchronizedTokenLocks: F[Option[SortedMap[Address, SortedSet[Signed[TokenLock]]]]]
  def getLastCurrencySnapshot: F[Option[Hashed[CurrencyIncrementalSnapshot]]]
  def getCurrencySnapshot(ordinal: SnapshotOrdinal): F[Option[Hashed[CurrencyIncrementalSnapshot]]]
  def getLastCurrencySnapshotCombined: F[Option[(Hashed[CurrencyIncrementalSnapshot], CurrencySnapshotInfo)]]
  def securityProvider: SecurityProvider[F]
  def getCurrencyId: F[CurrencyId]
  def getMetagraphL0Seedlist: Option[Set[SeedlistEntry]]
}
