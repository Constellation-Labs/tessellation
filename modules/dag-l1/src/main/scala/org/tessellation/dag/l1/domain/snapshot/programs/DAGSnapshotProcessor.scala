package org.tessellation.dag.l1.domain.snapshot.programs

import cats.Applicative
import cats.effect.Async
import cats.syntax.flatMap._

import org.tessellation.dag.l1.domain.address.storage.AddressStorage
import org.tessellation.dag.l1.domain.block.BlockStorage
import org.tessellation.dag.l1.domain.transaction.TransactionStorage
import org.tessellation.node.shared.domain.snapshot.SnapshotContextFunctions
import org.tessellation.node.shared.domain.snapshot.storage.LastSnapshotStorage
import org.tessellation.schema._
import org.tessellation.security.hash.ProofsHash
import org.tessellation.security.signature.Signed
import org.tessellation.security.{Hashed, Hasher, SecurityProvider}

import eu.timepit.refined.types.numeric.NonNegLong

object DAGSnapshotProcessor {

  def make[F[_]: Async: SecurityProvider](
    addressStorage: AddressStorage[F],
    blockStorage: BlockStorage[F],
    lastGlobalSnapshotStorage: LastSnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo],
    transactionStorage: TransactionStorage[F],
    globalSnapshotContextFns: SnapshotContextFunctions[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo],
    txHasher: Hasher[F]
  ): SnapshotProcessor[F, GlobalSnapshotStateProof, GlobalIncrementalSnapshot, GlobalSnapshotInfo] =
    new SnapshotProcessor[F, GlobalSnapshotStateProof, GlobalIncrementalSnapshot, GlobalSnapshotInfo] {

      import SnapshotProcessor._

      def onAlignedAtNewOrdinal(
        acceptedInMajority: Map[ProofsHash, (Hashed[Block], NonNegLong)],
        snapshot: Hashed[GlobalIncrementalSnapshot],
        state: GlobalSnapshotInfo
      ): F[Unit] = Applicative[F].unit

      def onAlignedAtNewHeight(
        acceptedInMajority: Map[ProofsHash, (Hashed[Block], NonNegLong)],
        snapshot: Hashed[GlobalIncrementalSnapshot],
        state: GlobalSnapshotInfo
      ): F[Unit] = Applicative[F].unit

      def onDownloadNeeded(state: GlobalSnapshotInfo, snapshot: Hashed[GlobalIncrementalSnapshot]): F[Unit] = Applicative[F].unit

      def onRedownloadNeeded(state: GlobalSnapshotInfo, snapshot: Hashed[GlobalIncrementalSnapshot]): F[Unit] = Applicative[F].unit

      def process(
        snapshot: Either[(Hashed[GlobalIncrementalSnapshot], GlobalSnapshotInfo), Hashed[GlobalIncrementalSnapshot]]
      )(implicit hasher: Hasher[F]): F[SnapshotProcessingResult] =
        checkAlignment(snapshot, blockStorage, lastGlobalSnapshotStorage, txHasher)
          .flatMap(processAlignment(_, blockStorage, transactionStorage, lastGlobalSnapshotStorage, addressStorage))

      def applySnapshotFn(
        lastState: GlobalSnapshotInfo,
        lastSnapshot: Signed[GlobalIncrementalSnapshot],
        snapshot: Signed[GlobalIncrementalSnapshot]
      )(implicit hasher: Hasher[F]): F[GlobalSnapshotInfo] = applyGlobalSnapshotFn(lastState, lastSnapshot, snapshot)

      def applyGlobalSnapshotFn(
        lastGlobalState: GlobalSnapshotInfo,
        lastGlobalSnapshot: Signed[GlobalIncrementalSnapshot],
        globalSnapshot: Signed[GlobalIncrementalSnapshot]
      )(implicit hasher: Hasher[F]): F[GlobalSnapshotInfo] =
        globalSnapshotContextFns.createContext(lastGlobalState, lastGlobalSnapshot, globalSnapshot)
    }
}
