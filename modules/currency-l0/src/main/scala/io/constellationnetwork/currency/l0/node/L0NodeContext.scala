package io.constellationnetwork.currency.l0.node

import cats.data.OptionT
import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.currency.dataApplication.{FeeTransaction, L0NodeContext}
import io.constellationnetwork.currency.schema.currency._
import io.constellationnetwork.domain.seedlist.SeedlistEntry
import io.constellationnetwork.node.shared.domain.snapshot.storage.{LastSyncGlobalSnapshotStorage, SnapshotStorage}
import io.constellationnetwork.node.shared.infrastructure.snapshot.storage.IdentifierStorage
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.swap.{AllowSpend, CurrencyId}
import io.constellationnetwork.schema.tokenLock.TokenLock
import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo, SnapshotOrdinal}
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed

object L0NodeContext {
  def make[F[_]: SecurityProvider: Async](
    snapshotStorage: SnapshotStorage[F, CurrencyIncrementalSnapshot, CurrencySnapshotInfo],
    hasherSelector: HasherSelector[F],
    lastGlobalSnapshotStorage: LastSyncGlobalSnapshotStorage[F],
    identifierStorage: IdentifierStorage[F],
    l0Seedlist: Option[Set[SeedlistEntry]]
  ): L0NodeContext[F] = new L0NodeContext[F] {

    def getCurrencyId: F[CurrencyId] =
      identifierStorage.get.map(_.toCurrencyId)

    def securityProvider: SecurityProvider[F] = SecurityProvider[F]

    def getLastCurrencySnapshot: F[Option[Hashed[CurrencyIncrementalSnapshot]]] =
      OptionT(snapshotStorage.headSnapshot)
        .semiflatMap(snapshot => hasherSelector.withCurrent(implicit hasher => snapshot.toHashed))
        .value

    def getCurrencySnapshot(ordinal: SnapshotOrdinal): F[Option[Hashed[CurrencyIncrementalSnapshot]]] =
      OptionT(snapshotStorage.get(ordinal))
        .semiflatMap(snapshot => hasherSelector.withCurrent(implicit hasher => snapshot.toHashed))
        .value

    def getLastCurrencySnapshotCombined: F[Option[(Hashed[CurrencyIncrementalSnapshot], CurrencySnapshotInfo)]] =
      OptionT(snapshotStorage.head).semiflatMap {
        case (snapshot, info) => hasherSelector.withCurrent(implicit hasher => snapshot.toHashed).map((_, info))
      }.value

    def getLastSynchronizedGlobalSnapshot: F[Option[GlobalIncrementalSnapshot]] =
      lastGlobalSnapshotStorage.getLastSynchronized

    def getLastSynchronizedGlobalSnapshotCombined: F[Option[(GlobalIncrementalSnapshot, GlobalSnapshotInfo)]] =
      lastGlobalSnapshotStorage.getLastSynchronizedCombined

    def getMetagraphL0Seedlist: Option[Set[SeedlistEntry]] = l0Seedlist

    def getLastSynchronizedAllowSpends: F[Option[SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]]]] =
      lastGlobalSnapshotStorage.getLastSynchronizedActiveAllowSpends

    def getLastSynchronizedTokenLocks: F[Option[SortedMap[Address, SortedSet[Signed[TokenLock]]]]] =
      lastGlobalSnapshotStorage.getLastSynchronizedActiveTokenLocks

    // The base context carries no fee transactions; the snapshot-scoped fee map is supplied by
    // L0NodeContext.withSnapshotFeeTransactions around each combine (live acceptance and replay).
    def getSnapshotFeeTransactions: F[Map[Hash, Signed[FeeTransaction]]] =
      Map.empty[Hash, Signed[FeeTransaction]].pure[F]
  }
}
