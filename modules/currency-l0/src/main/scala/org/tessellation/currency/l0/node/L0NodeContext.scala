package org.tessellation.currency.l0.node

import org.tessellation.currency.dataApplication.L0NodeContext
import org.tessellation.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo}
import org.tessellation.sdk.domain.snapshot.storage.LastSnapshotStorage
import org.tessellation.security.{Hashed, SecurityProvider}

object L0NodeContext {
  def make[F[_]: SecurityProvider](
    lastGlobalSnapshotStorage: LastSnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo]
  ): L0NodeContext[F] = new L0NodeContext[F] {
    def getLastGlobalSnapshot: F[Option[Hashed[GlobalIncrementalSnapshot]]] = lastGlobalSnapshotStorage.get

    def securityProvider: SecurityProvider[F] = SecurityProvider[F]
  }
}
