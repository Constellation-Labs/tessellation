package io.constellationnetwork.currency.l0.node

import cats.data.OptionT
import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.L0NodeContext
import io.constellationnetwork.currency.schema.currency._
import io.constellationnetwork.domain.seedlist.SeedlistEntry
import io.constellationnetwork.node.shared.app.NodeShared
import io.constellationnetwork.node.shared.domain.snapshot.storage.{LastSyncGlobalSnapshotStorage, SnapshotStorage}
import io.constellationnetwork.node.shared.infrastructure.snapshot.storage.IdentifierStorage
import io.constellationnetwork.schema.swap.CurrencyId
import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo, SnapshotOrdinal}
import io.constellationnetwork.security._

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
        .semiflatMap(snapshot => hasherSelector.forOrdinal(snapshot.ordinal)(implicit hasher => snapshot.toHashed))
        .value

    def getCurrencySnapshot(ordinal: SnapshotOrdinal): F[Option[Hashed[CurrencyIncrementalSnapshot]]] =
      OptionT(snapshotStorage.get(ordinal))
        .semiflatMap(snapshot => hasherSelector.forOrdinal(snapshot.ordinal)(implicit hasher => snapshot.toHashed))
        .value

    def getLastCurrencySnapshotCombined: F[Option[(Hashed[CurrencyIncrementalSnapshot], CurrencySnapshotInfo)]] =
      OptionT(snapshotStorage.head).semiflatMap {
        case (snapshot, info) => hasherSelector.forOrdinal(snapshot.ordinal)(implicit hasher => snapshot.toHashed).map((_, info))
      }.value

    def getLastSynchronizedGlobalSnapshot: F[Option[Hashed[GlobalIncrementalSnapshot]]] =
      getLastSynchronizedGlobalSnapshotCombined.map(_.map { case (snapshot, _) => snapshot })

    def getLastSynchronizedGlobalSnapshotCombined: F[Option[(Hashed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)]] =
      lastGlobalSnapshotStorage.getLastSynchronizedCombined

    def getMetagraphL0Seedlist: Option[Set[SeedlistEntry]] = l0Seedlist
  }
}
