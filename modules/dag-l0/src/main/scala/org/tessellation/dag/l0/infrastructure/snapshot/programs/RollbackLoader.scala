package org.tessellation.dag.l0.infrastructure.snapshot.programs

import java.security.KeyPair

import cats.effect.Async
import cats.syntax.all._

import org.tessellation.dag.l0.domain.snapshot.storages.SnapshotDownloadStorage
import org.tessellation.dag.l0.infrastructure.snapshot.GlobalSnapshotTraverse
import org.tessellation.json.JsonSerializer
import org.tessellation.kryo.KryoSerializer
import org.tessellation.node.shared.config.types.SnapshotConfig
import org.tessellation.node.shared.infrastructure.snapshot.GlobalSnapshotContextFunctions
import org.tessellation.node.shared.infrastructure.snapshot.storage.{SnapshotInfoLocalFileSystemStorage, SnapshotLocalFileSystemStorage}
import org.tessellation.schema._
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed
import org.tessellation.security.{HashSelect, HasherSelector, SecurityProvider}

import org.typelevel.log4cats.slf4j.Slf4jLogger

object RollbackLoader {

  def make[F[_]: Async: KryoSerializer: JsonSerializer: SecurityProvider: HasherSelector](
    keyPair: KeyPair,
    snapshotConfig: SnapshotConfig,
    incrementalGlobalSnapshotLocalFileSystemStorage: SnapshotLocalFileSystemStorage[F, GlobalIncrementalSnapshot],
    snapshotInfoLocalFileSystemStorage: SnapshotInfoLocalFileSystemStorage[F, GlobalSnapshotStateProof, GlobalSnapshotInfo],
    snapshotInfoLocalFileSystemKryoStorage: SnapshotInfoLocalFileSystemStorage[F, GlobalSnapshotStateProof, GlobalSnapshotInfoV2],
    snapshotStorage: SnapshotDownloadStorage[F],
    snapshotContextFunctions: GlobalSnapshotContextFunctions[F],
    hashSelect: HashSelect
  ): RollbackLoader[F] =
    new RollbackLoader[F](
      keyPair,
      snapshotConfig,
      incrementalGlobalSnapshotLocalFileSystemStorage,
      snapshotStorage: SnapshotDownloadStorage[F],
      snapshotContextFunctions,
      snapshotInfoLocalFileSystemStorage,
      snapshotInfoLocalFileSystemKryoStorage,
      hashSelect
    ) {}
}

sealed abstract class RollbackLoader[F[_]: Async: KryoSerializer: JsonSerializer: HasherSelector: SecurityProvider] private (
  keyPair: KeyPair,
  snapshotConfig: SnapshotConfig,
  incrementalGlobalSnapshotLocalFileSystemStorage: SnapshotLocalFileSystemStorage[F, GlobalIncrementalSnapshot],
  snapshotStorage: SnapshotDownloadStorage[F],
  snapshotContextFunctions: GlobalSnapshotContextFunctions[F],
  snapshotInfoLocalFileSystemStorage: SnapshotInfoLocalFileSystemStorage[F, GlobalSnapshotStateProof, GlobalSnapshotInfo],
  snapshotInfoLocalFileSystemKryoStorage: SnapshotInfoLocalFileSystemStorage[F, GlobalSnapshotStateProof, GlobalSnapshotInfoV2],
  hashSelect: HashSelect
) {

  private val logger = Slf4jLogger.getLogger[F]

  def load(rollbackHash: Hash): F[(GlobalSnapshotInfo, Signed[GlobalIncrementalSnapshot])] =
    SnapshotLocalFileSystemStorage.make[F, GlobalSnapshot](snapshotConfig.snapshotPath).flatMap {
      fullGlobalSnapshotLocalFileSystemStorage =>
        fullGlobalSnapshotLocalFileSystemStorage
          .read(rollbackHash)
          .flatMap {
            case None =>
              logger.info("Attempt to treat rollback hash as pointer to incremental global snapshot") >> {
                val snapshotTraverse = GlobalSnapshotTraverse
                  .make[F](
                    incrementalGlobalSnapshotLocalFileSystemStorage.read(_),
                    fullGlobalSnapshotLocalFileSystemStorage.read(_),
                    (ordinal: SnapshotOrdinal) =>
                      snapshotInfoLocalFileSystemStorage.read(ordinal).handleErrorWith { _ =>
                        snapshotInfoLocalFileSystemKryoStorage.read(ordinal).map(_.map(_.toGlobalSnapshotInfo))
                      },
                    snapshotContextFunctions,
                    rollbackHash
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
