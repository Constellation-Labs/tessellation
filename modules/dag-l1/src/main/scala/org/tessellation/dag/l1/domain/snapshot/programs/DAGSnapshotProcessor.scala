package org.tessellation.dag.l1.domain.snapshot.programs

import cats.effect.Async
import cats.syntax.flatMap._

import org.tessellation.dag.l1.domain.address.storage.AddressStorage
import org.tessellation.dag.l1.domain.block.BlockStorage
import org.tessellation.dag.l1.domain.transaction.TransactionStorage
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.block.DAGBlock
import org.tessellation.schema.transaction.DAGTransaction
import org.tessellation.schema.{GlobalSnapshotInfo, IncrementalGlobalSnapshot}
import org.tessellation.sdk.domain.snapshot.storage.LastSnapshotStorage
import org.tessellation.security.{Hashed, SecurityProvider}

object DAGSnapshotProcessor {

  def make[F[_]: Async: KryoSerializer: SecurityProvider](
    addressStorage: AddressStorage[F],
    blockStorage: BlockStorage[F, DAGBlock],
    lastGlobalSnapshotStorage: LastSnapshotStorage[F, IncrementalGlobalSnapshot, GlobalSnapshotInfo],
    transactionStorage: TransactionStorage[F, DAGTransaction]
  ): SnapshotProcessor[F, DAGTransaction, DAGBlock, IncrementalGlobalSnapshot, GlobalSnapshotInfo] =
    new SnapshotProcessor[F, DAGTransaction, DAGBlock, IncrementalGlobalSnapshot, GlobalSnapshotInfo] {

      import SnapshotProcessor._

      def process(snapshot: Hashed[IncrementalGlobalSnapshot], state: GlobalSnapshotInfo): F[SnapshotProcessingResult] =
        checkAlignment(snapshot, state, blockStorage, lastGlobalSnapshotStorage)
          .flatMap(processAlignment(snapshot, state, _, blockStorage, transactionStorage, lastGlobalSnapshotStorage, addressStorage))
    }
}
