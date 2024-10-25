package io.constellationnetwork.currency.l0.node

import cats.data.OptionT
import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.L0NodeContext
import io.constellationnetwork.currency.l0.snapshot.services.StateChannelBinarySender
import io.constellationnetwork.currency.l0.snapshot.storage.LastNGlobalSnapshotStorage
import io.constellationnetwork.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencySnapshotInfo}
import io.constellationnetwork.node.shared.domain.snapshot.storage.{LastSnapshotStorage, SnapshotStorage}
import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo, SnapshotOrdinal}
import io.constellationnetwork.security.{Hashed, HasherSelector, SecurityProvider}

object L0NodeContext {
  def make[F[_]: SecurityProvider: Async](
    snapshotStorage: SnapshotStorage[F, CurrencyIncrementalSnapshot, CurrencySnapshotInfo],
    hasherSelector: HasherSelector[F],
    stateChannelBinarySender: StateChannelBinarySender[F],
    lastNGlobalSnapshotStorage: LastSnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo] with LastNGlobalSnapshotStorage[F]
  ): L0NodeContext[F] = new L0NodeContext[F] {
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
      stateChannelBinarySender.getLastConfirmedWithinFixedWindow.flatMap { lastConfirmed =>
        lastConfirmed
          .map(_.confirmationProof.globalOrdinal)
          .flatTraverse(lastNGlobalSnapshotStorage.getCombined)
      }
  }
}
