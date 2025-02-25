package io.constellationnetwork.dag.l0.infrastructure.snapshot.programs

import java.security.KeyPair

import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.dag.l0.domain.snapshot.storages.SnapshotDownloadStorage
import io.constellationnetwork.dag.l0.infrastructure.snapshot.GlobalSnapshotTraverse
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.node.shared.config.types.SnapshotConfig
import io.constellationnetwork.node.shared.domain.snapshot.services.GlobalL0Service
import io.constellationnetwork.node.shared.infrastructure.snapshot.GlobalSnapshotContextFunctions
import io.constellationnetwork.node.shared.infrastructure.snapshot.storage.{
  GlobalSnapshotLocalFileSystemStorage,
  SnapshotInfoLocalFileSystemStorage,
  SnapshotLocalFileSystemStorage
}
import io.constellationnetwork.schema._
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed

import org.typelevel.log4cats.slf4j.Slf4jLogger

object RollbackLoader {

  def make[F[_]: Async: KryoSerializer: JsonSerializer: SecurityProvider: HasherSelector](
    keyPair: KeyPair,
    snapshotConfig: SnapshotConfig,
    incrementalGlobalSnapshotLocalFileSystemStorage: SnapshotLocalFileSystemStorage[F, GlobalIncrementalSnapshot],
    snapshotInfoLocalFileSystemStorage: SnapshotInfoLocalFileSystemStorage[F, GlobalSnapshotStateProof, GlobalSnapshotInfo],
    snapshotStorage: SnapshotDownloadStorage[F],
    snapshotContextFunctions: GlobalSnapshotContextFunctions[F],
    hashSelect: HashSelect,
    getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]]
  ): RollbackLoader[F] =
    new RollbackLoader[F](
      keyPair,
      snapshotConfig,
      incrementalGlobalSnapshotLocalFileSystemStorage,
      snapshotStorage: SnapshotDownloadStorage[F],
      snapshotContextFunctions,
      snapshotInfoLocalFileSystemStorage,
      hashSelect,
      getGlobalSnapshotByOrdinal
    ) {}
}

sealed abstract class RollbackLoader[F[_]: Async: KryoSerializer: JsonSerializer: HasherSelector: SecurityProvider] private (
  keyPair: KeyPair,
  snapshotConfig: SnapshotConfig,
  incrementalGlobalSnapshotLocalFileSystemStorage: SnapshotLocalFileSystemStorage[F, GlobalIncrementalSnapshot],
  snapshotStorage: SnapshotDownloadStorage[F],
  snapshotContextFunctions: GlobalSnapshotContextFunctions[F],
  snapshotInfoLocalFileSystemStorage: SnapshotInfoLocalFileSystemStorage[F, GlobalSnapshotStateProof, GlobalSnapshotInfo],
  hashSelect: HashSelect,
  getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]]
) {

  private val logger = Slf4jLogger.getLogger[F]

  def load(rollbackHash: Hash): F[(GlobalSnapshotInfo, Signed[GlobalIncrementalSnapshot])] =
    GlobalSnapshotLocalFileSystemStorage.make[F](snapshotConfig.snapshotPath).flatMap { fullGlobalSnapshotLocalFileSystemStorage =>
      fullGlobalSnapshotLocalFileSystemStorage
        .read(rollbackHash)
        .flatMap {
          case None =>
            logger.info("Attempt to treat rollback hash as pointer to incremental global snapshot") >> {
              val snapshotTraverse = GlobalSnapshotTraverse
                .make[F](
                  incrementalGlobalSnapshotLocalFileSystemStorage.read(_),
                  fullGlobalSnapshotLocalFileSystemStorage.read(_),
                  snapshotInfoLocalFileSystemStorage.read(_),
                  snapshotContextFunctions,
                  rollbackHash,
                  getGlobalSnapshotByOrdinal
                )
              snapshotTraverse.loadChain()
            }
          case Some(fullSnapshot) =>
            logger.info("Rollback hash points to full global snapshot") >>
              HasherSelector[F]
                .forOrdinal(fullSnapshot.ordinal) { implicit hasher =>
                  fullSnapshot
                    .toHashed[F]
                    .flatMap(GlobalSnapshot.mkFirstIncrementalSnapshot[F](_))
                    .flatMap { firstIncrementalSnapshot =>
                      Signed.forAsyncHasher[F, GlobalIncrementalSnapshot](firstIncrementalSnapshot, keyPair).map {
                        signedFirstIncrementalSnapshot =>
                          (GlobalSnapshotInfoV1.toGlobalSnapshotInfo(fullSnapshot.info), signedFirstIncrementalSnapshot)
                      }
                    }
                }
        }
        .flatTap {
          case (_, lastInc) =>
            logger.info(s"[Rollback] Cleanup for snapshots greater than ${lastInc.ordinal}") >>
              snapshotStorage.cleanupAbove(lastInc.ordinal)
        }
    }
}
