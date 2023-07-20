package org.tessellation.currency.l0.node

import cats.data.OptionT
import cats.effect.Async
import cats.syntax.functor._

import org.tessellation.currency.dataApplication.L0NodeContext
import org.tessellation.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencySnapshotInfo}
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo}
import org.tessellation.sdk.domain.snapshot.storage.{LastSnapshotStorage, SnapshotStorage}
import org.tessellation.security.{Hashed, SecurityProvider}

object L0NodeContext {
  def make[F[_]: SecurityProvider: KryoSerializer: Async](
    lastGlobalSnapshotStorage: LastSnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo],
    snapshotStorage: SnapshotStorage[F, CurrencyIncrementalSnapshot, CurrencySnapshotInfo]
  ): L0NodeContext[F] = new L0NodeContext[F] {
    def getLastGlobalSnapshot: F[Option[Hashed[GlobalIncrementalSnapshot]]] = lastGlobalSnapshotStorage.get

    def securityProvider: SecurityProvider[F] = SecurityProvider[F]

    def getLastCurrencySnapshot: F[Option[Hashed[CurrencyIncrementalSnapshot]]] =
      OptionT(snapshotStorage.headSnapshot)
        .semiflatMap(_.toHashed)
        .value

    def getLastCurrencySnapshotCombined: F[Option[(Hashed[CurrencyIncrementalSnapshot], CurrencySnapshotInfo)]] =
      OptionT(snapshotStorage.head).semiflatMap {
        case (snapshot, info) => snapshot.toHashed.map((_, info))
      }.value

  }
}
