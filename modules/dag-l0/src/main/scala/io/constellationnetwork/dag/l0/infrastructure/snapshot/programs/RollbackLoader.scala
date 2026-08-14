package io.constellationnetwork.dag.l0.infrastructure.snapshot.programs

import java.security.KeyPair

import cats.Parallel
import cats.effect.Async
import cats.syntax.all._

import scala.util.control.NoStackTrace

import io.constellationnetwork.dag.l0.domain.snapshot.storages.SnapshotDownloadStorage
import io.constellationnetwork.dag.l0.infrastructure.snapshot.GlobalSnapshotTraverse
import io.constellationnetwork.dag.l0.modules.{Services, Storages}
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.node.shared.config.types.SnapshotConfig
import io.constellationnetwork.node.shared.domain.collateral.LatestBalances
import io.constellationnetwork.node.shared.domain.snapshot.programs.Download
import io.constellationnetwork.node.shared.domain.snapshot.services.GlobalL0Service
import io.constellationnetwork.node.shared.domain.snapshot.storage.{LastNGlobalSnapshotStorage, LastSnapshotStorage, SnapshotStorage}
import io.constellationnetwork.node.shared.infrastructure.snapshot.GlobalSnapshotContextFunctions
import io.constellationnetwork.node.shared.infrastructure.snapshot.storage._
import io.constellationnetwork.node.shared.modules.SharedStorages
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.mpt.{GlobalStateKey, MptStore}
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed

import org.typelevel.log4cats.slf4j.Slf4jLogger

object RollbackLoader {

  sealed trait Source
  object Source {
    case object Incremental extends Source
    case object FullSnapshot extends Source
  }

  final case class PreflightIncrementalMissing(hash: Hash) extends NoStackTrace {
    override def getMessage: String = s"Recovery-plan preflight could not read exact incremental rollback hash=${hash.value}"
  }

  final case class PreflightSnapshotInfoMissing(ordinal: SnapshotOrdinal) extends NoStackTrace {
    override def getMessage: String = s"Recovery-plan preflight could not read snapshot info at ordinal=${ordinal.value.value}"
  }

  /** Keep the safety-critical ordering explicit and independently testable: a failed preflight cannot evaluate the mutation phase. */
  private[programs] def runPreflightThen[F[_]: Async, A](preflight: F[Unit], mutate: => F[A]): F[A] =
    preflight >> mutate

  def make[F[_]: Async: Parallel: KryoSerializer: JsonSerializer: SecurityProvider: HasherSelector](
    keyPair: KeyPair,
    snapshotConfig: SnapshotConfig,
    incrementalGlobalSnapshotLocalFileSystemStorage: SnapshotLocalFileSystemStorage[F, GlobalIncrementalSnapshot],
    snapshotInfoLocalFileSystemStorage: SnapshotInfoLocalFileSystemStorage[F, GlobalSnapshotStateProof, GlobalSnapshotInfo],
    snapshotStorage: SnapshotDownloadStorage[F],
    snapshotContextFunctions: GlobalSnapshotContextFunctions[F],
    getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]],
    globalSnapshotStorage: SnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo],
    lastNGlobalSnapshotStorage: LastNGlobalSnapshotStorage[F],
    lastGlobalSnapshotStorage: LastSnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo],
    combinedSnapshotCheckpointFileSystemStorage: CombinedSnapshotCheckpointFileSystemStorage[
      F,
      GlobalIncrementalSnapshot,
      GlobalSnapshotInfo
    ],
    mptStore: MptStore[F, GlobalStateKey]
  ): RollbackLoader[F] =
    new RollbackLoader[F](
      keyPair,
      snapshotConfig,
      incrementalGlobalSnapshotLocalFileSystemStorage,
      snapshotStorage: SnapshotDownloadStorage[F],
      snapshotContextFunctions,
      snapshotInfoLocalFileSystemStorage,
      getGlobalSnapshotByOrdinal,
      globalSnapshotStorage,
      lastNGlobalSnapshotStorage,
      lastGlobalSnapshotStorage,
      combinedSnapshotCheckpointFileSystemStorage,
      mptStore
    ) {}
}

sealed abstract class RollbackLoader[F[_]: Async: Parallel: KryoSerializer: JsonSerializer: HasherSelector: SecurityProvider] private (
  keyPair: KeyPair,
  snapshotConfig: SnapshotConfig,
  incrementalGlobalSnapshotLocalFileSystemStorage: SnapshotLocalFileSystemStorage[F, GlobalIncrementalSnapshot],
  snapshotStorage: SnapshotDownloadStorage[F],
  snapshotContextFunctions: GlobalSnapshotContextFunctions[F],
  snapshotInfoLocalFileSystemStorage: SnapshotInfoLocalFileSystemStorage[F, GlobalSnapshotStateProof, GlobalSnapshotInfo],
  getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]],
  globalSnapshotStorage: SnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo],
  lastNGlobalSnapshotStorage: LastNGlobalSnapshotStorage[F],
  lastGlobalSnapshotStorage: LastSnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo],
  combinedSnapshotCheckpointFileSystemStorage: CombinedSnapshotCheckpointFileSystemStorage[
    F,
    GlobalIncrementalSnapshot,
    GlobalSnapshotInfo
  ],
  mptStore: MptStore[F, GlobalStateKey]
) {

  private val logger = Slf4jLogger.getLogger[F]

  /** Resolve and load the rollback anchor. `preLoadValidate` is absent on ordinary rollback, preserving its exact path. Operator recovery
    * supplies a callback that runs against read-only local incremental/snapshot-info files before `GlobalSnapshotTraverse.loadChain` can
    * initialize snapshot storage or sync MPT state.
    */
  def load(
    rollbackHash: Hash,
    download: Download[F, GlobalIncrementalSnapshot],
    preLoadValidate: Option[(RollbackLoader.Source, GlobalSnapshotInfo, Signed[GlobalIncrementalSnapshot]) => F[Unit]] = None
  )(
    implicit globalStateProofSelector: GlobalStateProofSelector
  ): F[(GlobalSnapshotInfo, Signed[GlobalIncrementalSnapshot])] =
    GlobalSnapshotLocalFileSystemStorage.make[F](snapshotConfig.snapshotPath).flatMap { fullGlobalSnapshotLocalFileSystemStorage =>
      fullGlobalSnapshotLocalFileSystemStorage
        .read(rollbackHash)
        .flatMap {
          case None =>
            val loadIncremental = logger.info("Attempt to treat rollback hash as pointer to incremental global snapshot") >> {
              val snapshotTraverse = GlobalSnapshotTraverse
                .make[F](
                  incrementalGlobalSnapshotLocalFileSystemStorage.read(_),
                  fullGlobalSnapshotLocalFileSystemStorage.read(_),
                  snapshotInfoLocalFileSystemStorage.read(_),
                  snapshotContextFunctions,
                  rollbackHash,
                  getGlobalSnapshotByOrdinal,
                  globalSnapshotStorage,
                  lastNGlobalSnapshotStorage,
                  lastGlobalSnapshotStorage,
                  download,
                  mptStore
                )
              snapshotTraverse.loadChain()
            }

            preLoadValidate.fold(loadIncremental) { validate =>
              val preflight = for {
                exactSnapshot <- incrementalGlobalSnapshotLocalFileSystemStorage
                  .read(rollbackHash)
                  .flatMap(_.liftTo[F](RollbackLoader.PreflightIncrementalMissing(rollbackHash)))
                exactInfo <- snapshotInfoLocalFileSystemStorage
                  .read(exactSnapshot.ordinal)
                  .flatMap(_.liftTo[F](RollbackLoader.PreflightSnapshotInfoMissing(exactSnapshot.ordinal)))
                _ <- validate(RollbackLoader.Source.Incremental, exactInfo, exactSnapshot)
              } yield ()

              RollbackLoader.runPreflightThen(preflight, loadIncremental)
            }
          case Some(fullSnapshot) =>
            val loadFull = logger.info("Rollback hash points to full global snapshot") >>
              HasherSelector[F].withCurrent { implicit hasher =>
                fullSnapshot
                  .toHashed[F]
                  .flatMap(GlobalSnapshot.mkFirstIncrementalSnapshot[F](_))
                  .flatMap { firstIncrementalSnapshot =>
                    Signed.forAsyncHasher[F, GlobalIncrementalSnapshot](firstIncrementalSnapshot, keyPair).map {
                      signedFirstIncrementalSnapshot =>
                        (fullSnapshot.info.toGlobalSnapshotInfo, signedFirstIncrementalSnapshot)
                    }
                  }
              }

            preLoadValidate.fold(loadFull) { validate =>
              loadFull.flatTap { case (info, snapshot) => validate(RollbackLoader.Source.FullSnapshot, info, snapshot) }
            }
        }
        .flatTap {
          case (_, lastInc) =>
            logger.info(s"[Rollback] Cleanup for snapshots greater than ${lastInc.ordinal}") >>
              snapshotStorage.cleanupAbove(lastInc.ordinal) >>
              combinedSnapshotCheckpointFileSystemStorage.deleteAbove(lastInc.ordinal)
        }
    }
}
