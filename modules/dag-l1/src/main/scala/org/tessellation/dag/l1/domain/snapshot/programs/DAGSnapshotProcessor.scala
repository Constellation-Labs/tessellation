package org.tessellation.dag.l1.domain.snapshot.programs

import cats.effect.Async
import cats.syntax.flatMap._

import org.tessellation.dag.l1.domain.address.storage.AddressStorage
import org.tessellation.dag.l1.domain.block.BlockStorage
import org.tessellation.dag.l1.domain.transaction.TransactionStorage
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.block.DAGBlock
import org.tessellation.schema.transaction.DAGTransaction
import org.tessellation.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo, GlobalSnapshotStateProof}
import org.tessellation.sdk.domain.snapshot.SnapshotContextFunctions
import org.tessellation.sdk.domain.snapshot.storage.LastSnapshotStorage
import org.tessellation.security.signature.Signed
import org.tessellation.security.{Hashed, SecurityProvider}

object DAGSnapshotProcessor {

  def make[F[_]: Async: KryoSerializer: SecurityProvider](
    addressStorage: AddressStorage[F],
    blockStorage: BlockStorage[F, DAGBlock],
    lastGlobalSnapshotStorage: LastSnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo],
    transactionStorage: TransactionStorage[F, DAGTransaction],
    globalSnapshotContextFns: SnapshotContextFunctions[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo]
  ): SnapshotProcessor[F, DAGTransaction, DAGBlock, GlobalSnapshotStateProof, GlobalIncrementalSnapshot, GlobalSnapshotInfo] =
    new SnapshotProcessor[F, DAGTransaction, DAGBlock, GlobalSnapshotStateProof, GlobalIncrementalSnapshot, GlobalSnapshotInfo] {

      import SnapshotProcessor._

      def process(
        snapshot: Either[(Hashed[GlobalIncrementalSnapshot], GlobalSnapshotInfo), Hashed[GlobalIncrementalSnapshot]]
      ): F[SnapshotProcessingResult] =
        checkAlignment(snapshot, blockStorage, lastGlobalSnapshotStorage)
          .flatMap(processAlignment(_, blockStorage, transactionStorage, lastGlobalSnapshotStorage, addressStorage))

      def applySnapshotFn(
        lastState: GlobalSnapshotInfo,
        lastSnapshot: GlobalIncrementalSnapshot,
        snapshot: Signed[GlobalIncrementalSnapshot]
      ): F[GlobalSnapshotInfo] = applyGlobalSnapshotFn(lastState, lastSnapshot, snapshot)

      def applyGlobalSnapshotFn(
        lastGlobalState: GlobalSnapshotInfo,
        lastGlobalSnapshot: GlobalIncrementalSnapshot,
        globalSnapshot: Signed[GlobalIncrementalSnapshot]
      ): F[GlobalSnapshotInfo] = globalSnapshotContextFns.createContext(lastGlobalState, lastGlobalSnapshot, globalSnapshot)
    }
}
