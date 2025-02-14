package io.constellationnetwork.dag.l1.domain.snapshot.programs

import cats.Applicative
import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.dag.l1.domain.address.storage.AddressStorage
import io.constellationnetwork.dag.l1.domain.block.BlockStorage
import io.constellationnetwork.dag.l1.domain.transaction.TransactionStorage
import io.constellationnetwork.node.shared.config.types.LastGlobalSnapshotsSyncConfig
import io.constellationnetwork.node.shared.domain.snapshot.storage.{LastNGlobalSnapshotStorage, LastSnapshotStorage}
import io.constellationnetwork.node.shared.domain.snapshot.{SnapshotContextFunctions, Validator}
import io.constellationnetwork.node.shared.domain.swap.AllowSpendStorage
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
    globalSnapshotContextFns: SnapshotContextFunctions[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo],
    txHasher: Hasher[F]
  ): SnapshotProcessor[F, GlobalSnapshotStateProof, GlobalIncrementalSnapshot, GlobalSnapshotInfo] =
    new SnapshotProcessor[F, GlobalSnapshotStateProof, GlobalIncrementalSnapshot, GlobalSnapshotInfo] {

      import SnapshotProcessor._

      override def onDownload(snapshot: Hashed[GlobalIncrementalSnapshot], state: GlobalSnapshotInfo): F[Unit] =
        allowSpendStorage.initByRefs(state.lastAllowSpendRefs.map(_.toMap).getOrElse(Map.empty), snapshot.ordinal)

      override def onRedownload(snapshot: Hashed[GlobalIncrementalSnapshot], state: GlobalSnapshotInfo): F[Unit] =
        allowSpendStorage.replaceByRefs(state.lastAllowSpendRefs.map(_.toMap).getOrElse(Map.empty), snapshot.ordinal)

      def process(
        snapshot: Either[(Hashed[GlobalIncrementalSnapshot], GlobalSnapshotInfo), Hashed[GlobalIncrementalSnapshot]]
      )(implicit hasher: Hasher[F]): F[SnapshotProcessingResult] =
        snapshot match {
          case Left((globalSnapshot, globalState)) =>
            val globalSnapshotReference = SnapshotReference.fromHashedSnapshot(globalSnapshot)
            lastGlobalSnapshotStorage.getCombined.flatMap {
              case None =>
                val setNGlobalSnapshots = lastNGlobalSnapshotStorage
                  .setInitial(globalSnapshot, globalState)
                  .as[SnapshotProcessingResult](DownloadPerformed(globalSnapshotReference, Set.empty, Set.empty))

                lastNGlobalSnapshotStorage
                  .getLastN(globalSnapshot.ordinal, lastGlobalSnapshotsSyncConfig.minGlobalSnapshotsToParticipateConsensus.value)
                  .flatMap { lastGlobalSnapshots =>
                    checkAlignment(snapshot, blockStorage, lastGlobalSnapshotStorage, txHasher, lastGlobalSnapshots)
                      .flatMap(
                        processAlignment(
                          _,
                          blockStorage,
                          transactionStorage,
                          lastGlobalSnapshotStorage,
                          addressStorage
                        ).flatMap { results =>
                          setNGlobalSnapshots.as(results)
                        }
                      )
                  }
              case _ => (new Throwable("unexpected state")).raiseError[F, SnapshotProcessingResult]
            }
          case Right(globalSnapshot) =>
            val globalSnapshotReference = SnapshotReference.fromHashedSnapshot(globalSnapshot)
            lastGlobalSnapshotStorage.getCombined.flatMap {
              case Some((lastGlobalSnapshot, lastGlobalState)) =>
                Validator.compare(lastGlobalSnapshot, globalSnapshot.signed.value) match {
                  case _: Validator.Next =>
                    val setNGlobalSnapshots = lastNGlobalSnapshotStorage
                      .set(globalSnapshot, lastGlobalState)
                      .as[SnapshotProcessingResult](DownloadPerformed(globalSnapshotReference, Set.empty, Set.empty))

                    lastNGlobalSnapshotStorage
                      .getLastN(globalSnapshot.ordinal, lastGlobalSnapshotsSyncConfig.minGlobalSnapshotsToParticipateConsensus.value)
                      .flatMap { lastGlobalSnapshots =>
                        checkAlignment(snapshot, blockStorage, lastGlobalSnapshotStorage, txHasher, lastGlobalSnapshots)
                          .flatMap(
                            processAlignment(
                              _,
                              blockStorage,
                              transactionStorage,
                              lastGlobalSnapshotStorage,
                              addressStorage
                            ).flatMap { results =>
                              setNGlobalSnapshots.as(results)
                            }
                          )
                      }
                  case Validator.NotNext =>
                    Applicative[F].pure(SnapshotIgnored(globalSnapshotReference))
                }
              case None => (new Throwable("unexpected state")).raiseError[F, SnapshotProcessingResult]
            }
        }

      def applySnapshotFn(
        lastState: GlobalSnapshotInfo,
        lastSnapshot: Signed[GlobalIncrementalSnapshot],
        snapshot: Signed[GlobalIncrementalSnapshot],
        lastGlobalSnapshots: Option[List[Hashed[GlobalIncrementalSnapshot]]]
      )(implicit hasher: Hasher[F]): F[GlobalSnapshotInfo] = applyGlobalSnapshotFn(lastState, lastSnapshot, snapshot, lastGlobalSnapshots)

      def applyGlobalSnapshotFn(
        lastGlobalState: GlobalSnapshotInfo,
        lastGlobalSnapshot: Signed[GlobalIncrementalSnapshot],
        globalSnapshot: Signed[GlobalIncrementalSnapshot],
        lastGlobalSnapshots: Option[List[Hashed[GlobalIncrementalSnapshot]]]
      )(implicit hasher: Hasher[F]): F[GlobalSnapshotInfo] =
        globalSnapshotContextFns.createContext(lastGlobalState, lastGlobalSnapshot, globalSnapshot, lastGlobalSnapshots)
    }
}
