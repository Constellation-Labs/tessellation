package org.tessellation.infrastructure.snapshot.programs

import java.security.KeyPair

import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tessellation.domain.snapshot.storages.SnapshotDownloadStorage
import org.tessellation.infrastructure.snapshot.GlobalSnapshotTraverse
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema._
import org.tessellation.sdk.config.types.SnapshotConfig
import org.tessellation.sdk.infrastructure.snapshot.GlobalSnapshotContextFunctions
import org.tessellation.sdk.infrastructure.snapshot.storage.{SnapshotInfoLocalFileSystemStorage, SnapshotLocalFileSystemStorage}
import org.tessellation.security.SecurityProvider
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed

import org.typelevel.log4cats.slf4j.Slf4jLogger

object RollbackLoader {

  def make[F[_]: Async: KryoSerializer: SecurityProvider](
    keyPair: KeyPair,
    snapshotConfig: SnapshotConfig,
    incrementalGlobalSnapshotLocalFileSystemStorage: SnapshotLocalFileSystemStorage[F, GlobalIncrementalSnapshot],
    snapshotInfoLocalFileSystemStorage: SnapshotInfoLocalFileSystemStorage[F, GlobalSnapshotStateProof, GlobalSnapshotInfo],
    snapshotStorage: SnapshotDownloadStorage[F],
    snapshotContextFunctions: GlobalSnapshotContextFunctions[F]
  ): RollbackLoader[F] =
    new RollbackLoader[F](
      keyPair,
      snapshotConfig,
      incrementalGlobalSnapshotLocalFileSystemStorage,
      snapshotStorage: SnapshotDownloadStorage[F],
      snapshotContextFunctions,
      snapshotInfoLocalFileSystemStorage
    ) {}
}

sealed abstract class RollbackLoader[F[_]: Async: KryoSerializer: SecurityProvider] private (
  keyPair: KeyPair,
  snapshotConfig: SnapshotConfig,
  incrementalGlobalSnapshotLocalFileSystemStorage: SnapshotLocalFileSystemStorage[F, GlobalIncrementalSnapshot],
  snapshotStorage: SnapshotDownloadStorage[F],
  snapshotContextFunctions: GlobalSnapshotContextFunctions[F],
  snapshotInfoLocalFileSystemStorage: SnapshotInfoLocalFileSystemStorage[F, GlobalSnapshotStateProof, GlobalSnapshotInfo]
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
                    snapshotInfoLocalFileSystemStorage.read(_),
                    snapshotContextFunctions,
                    rollbackHash
                  )
                snapshotTraverse.loadChain()
              }
            case Some(fullSnapshot) =>
              logger.info("Rollback hash points to full global snapshot") >>
                fullSnapshot.toHashed[F].flatMap(GlobalSnapshot.mkFirstIncrementalSnapshot[F](_)).flatMap { firstIncrementalSnapshot =>
                  Signed.forAsyncKryo[F, GlobalIncrementalSnapshot](firstIncrementalSnapshot, keyPair).map {
                    signedFirstIncrementalSnapshot =>
                      (GlobalSnapshotInfoV1.toGlobalSnapshotInfo(fullSnapshot.info), signedFirstIncrementalSnapshot)
                  }
                }
          }
          .flatTap {
            case (_, lastInc) =>
              logger.info(s"[Rollback] Cleanup for snapshots greater than ${lastInc.ordinal}") >>
                snapshotStorage.backupPersistedAbove(lastInc.ordinal) >>
                snapshotInfoLocalFileSystemStorage.deleteAbove(lastInc.ordinal)
          }
    }
}
