package org.tessellation.currency.l0.node

import cats.data.OptionT
import cats.effect.Async
import cats.syntax.functor._

import org.tessellation.currency.dataApplication.L0NodeContext
import org.tessellation.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencySnapshotInfo}
import org.tessellation.node.shared.domain.snapshot.storage.SnapshotStorage
import org.tessellation.schema.SnapshotOrdinal
import org.tessellation.security.{Hashed, HasherSelector, SecurityProvider}

object L0NodeContext {
  def make[F[_]: SecurityProvider: Async](
    snapshotStorage: SnapshotStorage[F, CurrencyIncrementalSnapshot, CurrencySnapshotInfo],
    hasherSelector: HasherSelector[F]
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

  }
}
