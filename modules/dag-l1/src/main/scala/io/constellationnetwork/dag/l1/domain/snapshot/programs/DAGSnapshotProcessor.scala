package io.constellationnetwork.dag.l1.domain.snapshot.programs

import cats.Parallel
import cats.effect.Async
import cats.effect.std.Mutex
import cats.syntax.all._

import io.constellationnetwork.dag.l1.domain.address.storage.AddressStorage
import io.constellationnetwork.dag.l1.domain.block.BlockStorage
import io.constellationnetwork.dag.l1.domain.transaction.TransactionStorage
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.node.shared.config.types.LastGlobalSnapshotsSyncConfig
import io.constellationnetwork.node.shared.domain.globalAlignment.GlobalL0AlignmentStorage
import io.constellationnetwork.node.shared.domain.snapshot.SnapshotContextFunctions
import io.constellationnetwork.node.shared.domain.snapshot.services.GlobalL0Service
import io.constellationnetwork.node.shared.domain.snapshot.storage.{LastNGlobalSnapshotStorage, LastSnapshotStorage}
import io.constellationnetwork.node.shared.domain.swap.AllowSpendStorage
import io.constellationnetwork.node.shared.domain.tokenlock.TokenLockStorage
import io.constellationnetwork.node.shared.modules.SharedStorages
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.mpt.{GlobalStateKey, MptStore}
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.{Hashed, Hasher, SecurityProvider}

import io.circe.Json

object DAGSnapshotProcessor {

  def make[F[_]: Async: Parallel: SecurityProvider: JsonSerializer](
    addressStorage: AddressStorage[F],
    blockStorage: BlockStorage[F],
    lastGlobalSnapshotStorage: LastSnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo],
    lastNGlobalSnapshotStorage: LastNGlobalSnapshotStorage[F],
    transactionStorage: TransactionStorage[F],
    allowSpendStorage: AllowSpendStorage[F],
    tokenLockStorage: TokenLockStorage[F],
    globalSnapshotContextFns: SnapshotContextFunctions[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo],
    txHasher: Hasher[F],
    getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]],
    l0Service: GlobalL0Service[F],
    globalL0AlignmentStorage: GlobalL0AlignmentStorage[F],
    mptStore: MptStore[F, GlobalStateKey],
    storageMutationLock: Mutex[F]
  ): SnapshotProcessor[F, GlobalSnapshotStateProof, GlobalIncrementalSnapshot, GlobalSnapshotInfo] =
    new SnapshotProcessor[F, GlobalSnapshotStateProof, GlobalIncrementalSnapshot, GlobalSnapshotInfo] {

      import SnapshotProcessor._

      override def onDownload(snapshot: Hashed[GlobalIncrementalSnapshot], state: GlobalSnapshotInfo): F[Unit] =
        allowSpendStorage.initByRefs(state.lastAllowSpendRefs.map(_.toMap).getOrElse(Map.empty), snapshot.ordinal) >>
          tokenLockStorage.initByRefs(state.lastTokenLockRefs.map(_.toMap).getOrElse(Map.empty), snapshot.ordinal)

      override def onRedownload(snapshot: Hashed[GlobalIncrementalSnapshot], state: GlobalSnapshotInfo): F[Unit] =
        allowSpendStorage.replaceByRefs(state.lastAllowSpendRefs.map(_.toMap).getOrElse(Map.empty), snapshot.ordinal) >>
          tokenLockStorage.replaceByRefs(state.lastTokenLockRefs.map(_.toMap).getOrElse(Map.empty), snapshot.ordinal)

      override def setInitialLastNSnapshots(snapshot: Hashed[GlobalIncrementalSnapshot], state: GlobalSnapshotInfo): F[Unit] =
        lastNGlobalSnapshotStorage.setInitialFetchingGL0(
          snapshot,
          state,
          l0Service.asLeft.some,
          none
        )

      override def setLastNSnapshots(snapshot: Hashed[GlobalIncrementalSnapshot], state: GlobalSnapshotInfo): F[Unit] =
        lastNGlobalSnapshotStorage.set(snapshot, state)

      def process(
        snapshot: Either[(Hashed[GlobalIncrementalSnapshot], GlobalSnapshotInfo), Hashed[GlobalIncrementalSnapshot]]
      )(
        implicit hasher: Hasher[F],
        stateProofSelector: StateProofSelector
      ): F[SnapshotProcessingResult] =
        checkAlignment(
          snapshot,
          blockStorage,
          lastGlobalSnapshotStorage,
          txHasher,
          getGlobalSnapshotByOrdinal,
          globalL0AlignmentStorage
        )
          .flatMap(
            processAlignment(
              _,
              blockStorage,
              transactionStorage,
              allowSpendStorage,
              tokenLockStorage,
              lastGlobalSnapshotStorage,
              addressStorage,
              mptStore,
              storageMutationLock
            )
          )

      def applySnapshotFn(
        lastState: GlobalSnapshotInfo,
        lastSnapshot: Signed[GlobalIncrementalSnapshot],
        snapshot: Signed[GlobalIncrementalSnapshot],
        getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]]
      )(implicit hasher: Hasher[F]): F[GlobalSnapshotInfo] =
        applyGlobalSnapshotFn(lastState, lastSnapshot, snapshot, getGlobalSnapshotByOrdinal)

      def applyGlobalSnapshotFn(
        lastGlobalState: GlobalSnapshotInfo,
        lastGlobalSnapshot: Signed[GlobalIncrementalSnapshot],
        globalSnapshot: Signed[GlobalIncrementalSnapshot],
        getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]]
      )(implicit hasher: Hasher[F]): F[GlobalSnapshotInfo] =
        globalSnapshotContextFns.createContext(
          lastGlobalState,
          lastGlobalSnapshot,
          globalSnapshot,
          getGlobalSnapshotByOrdinal
        )
    }
}
