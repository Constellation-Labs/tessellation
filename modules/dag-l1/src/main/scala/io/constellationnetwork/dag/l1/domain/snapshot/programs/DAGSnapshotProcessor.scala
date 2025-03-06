package io.constellationnetwork.dag.l1.domain.snapshot.programs

import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.dag.l1.domain.address.storage.AddressStorage
import io.constellationnetwork.dag.l1.domain.block.BlockStorage
import io.constellationnetwork.dag.l1.domain.transaction.TransactionStorage
import io.constellationnetwork.node.shared.config.types.LastGlobalSnapshotsSyncConfig
import io.constellationnetwork.node.shared.domain.snapshot.SnapshotContextFunctions
import io.constellationnetwork.node.shared.domain.snapshot.services.GlobalL0Service
import io.constellationnetwork.node.shared.domain.snapshot.storage.{LastNGlobalSnapshotStorage, LastSnapshotStorage}
import io.constellationnetwork.node.shared.domain.swap.AllowSpendStorage
import io.constellationnetwork.node.shared.domain.tokenlock.TokenLockStorage
import io.constellationnetwork.schema._
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.{Hashed, Hasher, SecurityProvider}

object DAGSnapshotProcessor {

  def make[F[_]: Async: SecurityProvider](
    lastGlobalSnapshotsSyncConfig: LastGlobalSnapshotsSyncConfig,
    addressStorage: AddressStorage[F],
    blockStorage: BlockStorage[F],
    lastGlobalSnapshotStorage: LastSnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo],
    lastNGlobalSnapshotStorage: LastNGlobalSnapshotStorage[F],
    transactionStorage: TransactionStorage[F],
    allowSpendStorage: AllowSpendStorage[F],
    tokenLockStorage: TokenLockStorage[F],
    globalSnapshotContextFns: SnapshotContextFunctions[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo],
    txHasher: Hasher[F],
    getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]]
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
        lastNGlobalSnapshotStorage.setInitial(snapshot, state)

      override def setLastNSnapshots(snapshot: Hashed[GlobalIncrementalSnapshot], state: GlobalSnapshotInfo): F[Unit] =
        lastNGlobalSnapshotStorage.set(snapshot, state)

      def process(
        snapshot: Either[(Hashed[GlobalIncrementalSnapshot], GlobalSnapshotInfo), Hashed[GlobalIncrementalSnapshot]]
      )(implicit hasher: Hasher[F]): F[SnapshotProcessingResult] = {
        val snapshotOrdinal = snapshot match {
          case Left((snapshot, _)) => snapshot.ordinal
          case Right(value)        => value.ordinal
        }
        lastNGlobalSnapshotStorage
          .getLastN(snapshotOrdinal, lastGlobalSnapshotsSyncConfig.minGlobalSnapshotsToParticipateConsensus.value)
          .flatMap { lastGlobalSnapshots =>
            checkAlignment(snapshot, blockStorage, lastGlobalSnapshotStorage, txHasher, lastGlobalSnapshots, getGlobalSnapshotByOrdinal)
              .flatMap(
                processAlignment(
                  _,
                  blockStorage,
                  transactionStorage,
                  allowSpendStorage,
                  tokenLockStorage,
                  lastGlobalSnapshotStorage,
                  addressStorage
                )
              )
          }
      }

      def applySnapshotFn(
        lastState: GlobalSnapshotInfo,
        lastSnapshot: Signed[GlobalIncrementalSnapshot],
        snapshot: Signed[GlobalIncrementalSnapshot],
        lastGlobalSnapshots: Option[List[Hashed[GlobalIncrementalSnapshot]]],
        getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]]
      )(implicit hasher: Hasher[F]): F[GlobalSnapshotInfo] =
        applyGlobalSnapshotFn(lastState, lastSnapshot, snapshot, lastGlobalSnapshots, getGlobalSnapshotByOrdinal)

      def applyGlobalSnapshotFn(
        lastGlobalState: GlobalSnapshotInfo,
        lastGlobalSnapshot: Signed[GlobalIncrementalSnapshot],
        globalSnapshot: Signed[GlobalIncrementalSnapshot],
        lastGlobalSnapshots: Option[List[Hashed[GlobalIncrementalSnapshot]]],
        getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]]
      )(implicit hasher: Hasher[F]): F[GlobalSnapshotInfo] =
        globalSnapshotContextFns.createContext(
          lastGlobalState,
          lastGlobalSnapshot,
          globalSnapshot,
          lastGlobalSnapshots,
          getGlobalSnapshotByOrdinal
        )
    }
}
