package org.tessellation.dag.l1.domain.snapshot.programs

import cats.effect.Async
import cats.syntax.flatMap._

import org.tessellation.dag.l1.domain.address.storage.AddressStorage
import org.tessellation.dag.l1.domain.block.BlockStorage
import org.tessellation.dag.l1.domain.transaction.TransactionStorage
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.GlobalSnapshot
import org.tessellation.schema.block.DAGBlock
import org.tessellation.schema.transaction.DAGTransaction
import org.tessellation.sdk.domain.snapshot.storage.LastSnapshotStorage
import org.tessellation.security.{Hashed, SecurityProvider}

object DAGSnapshotProcessor {

  def make[F[_]: Async: KryoSerializer: SecurityProvider](
    addressStorage: AddressStorage[F],
    blockStorage: BlockStorage[F, DAGBlock],
    lastGlobalSnapshotStorage: LastSnapshotStorage[F, GlobalSnapshot],
    transactionStorage: TransactionStorage[F, DAGTransaction]
  ): SnapshotProcessor[F, DAGTransaction, DAGBlock, GlobalSnapshot] =
    new SnapshotProcessor[F, DAGTransaction, DAGBlock, GlobalSnapshot] {

      import SnapshotProcessor._

      def process(globalSnapshot: Hashed[GlobalSnapshot]): F[SnapshotProcessingResult] =
        checkAlignment(globalSnapshot, blockStorage, lastGlobalSnapshotStorage)
          .flatMap(processAlignment(globalSnapshot, _, blockStorage, transactionStorage, lastGlobalSnapshotStorage, addressStorage))
    }
}
