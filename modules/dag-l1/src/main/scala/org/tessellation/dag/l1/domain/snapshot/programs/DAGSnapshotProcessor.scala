package org.tessellation.dag.l1.domain.snapshot.programs

import cats.effect.Async
import cats.syntax.flatMap._

import org.tessellation.dag.l1.domain.address.storage.AddressStorage
import org.tessellation.dag.l1.domain.block.BlockStorage
import org.tessellation.dag.l1.domain.transaction.TransactionStorage
import org.tessellation.node.shared.domain.snapshot.SnapshotContextFunctions
import org.tessellation.node.shared.domain.snapshot.storage.LastSnapshotStorage
import org.tessellation.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo, GlobalSnapshotStateProof}
import org.tessellation.security.signature.Signed
import org.tessellation.security.{Hashed, Hasher, SecurityProvider}

object DAGSnapshotProcessor {

  def make[F[_]: Async: Hasher: SecurityProvider](
    addressStorage: AddressStorage[F],
    blockStorage: BlockStorage[F],
    lastGlobalSnapshotStorage: LastSnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo],
    transactionStorage: TransactionStorage[F],
    globalSnapshotContextFns: SnapshotContextFunctions[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo]
  ): SnapshotProcessor[F, GlobalSnapshotStateProof, GlobalIncrementalSnapshot, GlobalSnapshotInfo] =
    new SnapshotProcessor[F, GlobalSnapshotStateProof, GlobalIncrementalSnapshot, GlobalSnapshotInfo] {

      import SnapshotProcessor._

      def process(
        snapshot: Either[(Hashed[GlobalIncrementalSnapshot], GlobalSnapshotInfo), Hashed[GlobalIncrementalSnapshot]]
      ): F[SnapshotProcessingResult] =
        checkAlignment(snapshot, blockStorage, lastGlobalSnapshotStorage)
          .flatMap(processAlignment(_, blockStorage, transactionStorage, lastGlobalSnapshotStorage, addressStorage))

      def applySnapshotFn(
        lastState: GlobalSnapshotInfo,
        lastSnapshot: Signed[GlobalIncrementalSnapshot],
        snapshot: Signed[GlobalIncrementalSnapshot]
      ): F[GlobalSnapshotInfo] = applyGlobalSnapshotFn(lastState, lastSnapshot, snapshot)

      def applyGlobalSnapshotFn(
        lastGlobalState: GlobalSnapshotInfo,
        lastGlobalSnapshot: Signed[GlobalIncrementalSnapshot],
        globalSnapshot: Signed[GlobalIncrementalSnapshot]
      ): F[GlobalSnapshotInfo] = globalSnapshotContextFns.createContext(lastGlobalState, lastGlobalSnapshot, globalSnapshot)
    }
}
