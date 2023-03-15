package org.tessellation.dag.l1.domain.snapshot.programs

import cats.effect.Async
import cats.syntax.applicative._
import cats.syntax.flatMap._

import org.tessellation.dag.l1.domain.address.storage.AddressStorage
import org.tessellation.dag.l1.domain.block.BlockStorage
import org.tessellation.dag.l1.domain.transaction.TransactionStorage
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.block.DAGBlock
import org.tessellation.schema.transaction.DAGTransaction
import org.tessellation.schema.{GlobalSnapshotInfo, IncrementalGlobalSnapshot}
import org.tessellation.sdk.domain.snapshot.storage.LastSnapshotStorage
import org.tessellation.security.signature.Signed
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

      def process(
        snapshot: Either[(Hashed[IncrementalGlobalSnapshot], GlobalSnapshotInfo), Hashed[IncrementalGlobalSnapshot]]
      ): F[SnapshotProcessingResult] =
        checkAlignment(snapshot, blockStorage, lastGlobalSnapshotStorage)
          .flatMap(processAlignment(_, blockStorage, transactionStorage, lastGlobalSnapshotStorage, addressStorage))

      def applySnapshotFn(
        lastState: GlobalSnapshotInfo,
        lastSnapshot: IncrementalGlobalSnapshot,
        snapshot: Signed[IncrementalGlobalSnapshot]
      ): F[GlobalSnapshotInfo] = applyGlobalSnapshotFn(lastState, lastSnapshot, snapshot)

      def applyGlobalSnapshotFn(
        lastGlobalState: GlobalSnapshotInfo,
        lastGlobalSnapshot: IncrementalGlobalSnapshot,
        globalSnapshot: Signed[IncrementalGlobalSnapshot]
      ): F[GlobalSnapshotInfo] = ???
    }
}
