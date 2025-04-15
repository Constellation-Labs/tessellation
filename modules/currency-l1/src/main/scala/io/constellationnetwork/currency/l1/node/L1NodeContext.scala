package io.constellationnetwork.currency.l1.node

import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.L1NodeContext
import io.constellationnetwork.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencySnapshotInfo}
import io.constellationnetwork.node.shared.domain.snapshot.storage.LastSnapshotStorage
import io.constellationnetwork.node.shared.infrastructure.snapshot.storage.IdentifierStorage
import io.constellationnetwork.schema.swap.CurrencyId
import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo}
import io.constellationnetwork.security.{Hashed, SecurityProvider}

object L1NodeContext {
  def make[F[_]: SecurityProvider: Async](
    lastGlobalSnapshotStorage: LastSnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo],
    lastCurrencySnapshotStorage: LastSnapshotStorage[F, CurrencyIncrementalSnapshot, CurrencySnapshotInfo],
    identifierStorage: IdentifierStorage[F]
  ): L1NodeContext[F] =
    new L1NodeContext[F] {
      def getLastGlobalSnapshot: F[Option[Hashed[GlobalIncrementalSnapshot]]] = lastGlobalSnapshotStorage.get

      def getLastCurrencySnapshot: F[Option[Hashed[CurrencyIncrementalSnapshot]]] = lastCurrencySnapshotStorage.get

      def getLastCurrencySnapshotCombined: F[Option[(Hashed[CurrencyIncrementalSnapshot], CurrencySnapshotInfo)]] =
        lastCurrencySnapshotStorage.getCombined

      def getCurrencyId: F[CurrencyId] =
        identifierStorage.get.map(_.toCurrencyId)

      def securityProvider: SecurityProvider[F] = SecurityProvider[F]
    }
}
