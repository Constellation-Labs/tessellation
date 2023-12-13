package org.tessellation.currency.l1.node

import org.tessellation.currency.dataApplication.{DataUpdate, L1NodeContext}
import org.tessellation.currency.l1.modules.Queues
import org.tessellation.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencySnapshotInfo}
import org.tessellation.node.shared.domain.snapshot.storage.LastSnapshotStorage
import org.tessellation.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo}
import org.tessellation.security.signature.Signed
import org.tessellation.security.{Hashed, SecurityProvider}

object L1NodeContext {
  def make[F[_]: SecurityProvider](
    lastGlobalSnapshotStorage: LastSnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo],
    lastCurrencySnapshotStorage: LastSnapshotStorage[F, CurrencyIncrementalSnapshot, CurrencySnapshotInfo],
    queues: Queues[F]
  ): L1NodeContext[F] =
    new L1NodeContext[F] {
      def addDataUpdate(update: Signed[DataUpdate]): F[Unit] = queues.dataUpdates.offer(update)

      def getLastGlobalSnapshot: F[Option[Hashed[GlobalIncrementalSnapshot]]] = lastGlobalSnapshotStorage.get

      def getLastCurrencySnapshot: F[Option[Hashed[CurrencyIncrementalSnapshot]]] = lastCurrencySnapshotStorage.get

      def getLastCurrencySnapshotCombined: F[Option[(Hashed[CurrencyIncrementalSnapshot], CurrencySnapshotInfo)]] =
        lastCurrencySnapshotStorage.getCombined

      def securityProvider: SecurityProvider[F] = SecurityProvider[F]
    }
}
