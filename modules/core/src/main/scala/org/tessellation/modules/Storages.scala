package org.tessellation.modules

import cats.effect.kernel.Async
import cats.effect.std.Supervisor
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tessellation.domain.snapshot.storages.SnapshotDownloadStorage
import org.tessellation.infrastructure.snapshot.SnapshotDownloadStorage
import org.tessellation.infrastructure.trust.storage.TrustStorage
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.trust.PeerObservationAdjustmentUpdateBatch
import org.tessellation.schema.{GlobalIncrementalSnapshot, GlobalSnapshot, GlobalSnapshotInfo}
import org.tessellation.sdk.config.types.{SdkConfig, SnapshotConfig}
import org.tessellation.sdk.domain.cluster.storage.{ClusterStorage, SessionStorage}
import org.tessellation.sdk.domain.collateral.LatestBalances
import org.tessellation.sdk.domain.node.NodeStorage
import org.tessellation.sdk.domain.seedlist.SeedlistEntry
import org.tessellation.sdk.domain.snapshot.storage.SnapshotStorage
import org.tessellation.sdk.domain.trust.storage.TrustStorage
import org.tessellation.sdk.infrastructure.gossip.RumorStorage
import org.tessellation.sdk.infrastructure.snapshot.storage.{SnapshotLocalFileSystemStorage, SnapshotStorage}
import org.tessellation.sdk.modules.SdkStorages

object Storages {

  def make[F[_]: Async: KryoSerializer: Supervisor](
    sdkStorages: SdkStorages[F],
    sdkConfig: SdkConfig,
    seedlist: Option[Set[SeedlistEntry]],
    snapshotConfig: SnapshotConfig,
    trustUpdates: Option[PeerObservationAdjustmentUpdateBatch]
  ): F[Storages[F]] =
    for {
      trustStorage <- TrustStorage.make[F](trustUpdates, sdkConfig.trustStorage, seedlist)
      incrementalGlobalSnapshotTmpLocalFileSystemStorage <- SnapshotLocalFileSystemStorage.make[F, GlobalIncrementalSnapshot](
        snapshotConfig.incrementalTmpSnapshotPath
      )
      incrementalGlobalSnapshotPersistedLocalFileSystemStorage <- SnapshotLocalFileSystemStorage.make[F, GlobalIncrementalSnapshot](
        snapshotConfig.incrementalPersistedSnapshotPath
      )
      fullGlobalSnapshotLocalFileSystemStorage <- SnapshotLocalFileSystemStorage.make[F, GlobalSnapshot](
        snapshotConfig.snapshotPath
      )
      globalSnapshotStorage <- SnapshotStorage.make[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo](
        incrementalGlobalSnapshotPersistedLocalFileSystemStorage,
        snapshotConfig.inMemoryCapacity
      )
      snapshotDownloadStorage = SnapshotDownloadStorage
        .make[F](
          incrementalGlobalSnapshotTmpLocalFileSystemStorage,
          incrementalGlobalSnapshotPersistedLocalFileSystemStorage,
          fullGlobalSnapshotLocalFileSystemStorage
        )
    } yield
      new Storages[F](
        cluster = sdkStorages.cluster,
        node = sdkStorages.node,
        session = sdkStorages.session,
        rumor = sdkStorages.rumor,
        trust = trustStorage,
        globalSnapshot = globalSnapshotStorage,
        fullGlobalSnapshot = fullGlobalSnapshotLocalFileSystemStorage,
        incrementalGlobalSnapshotLocalFileSystemStorage = incrementalGlobalSnapshotPersistedLocalFileSystemStorage,
        snapshotDownload = snapshotDownloadStorage
      ) {}
}

sealed abstract class Storages[F[_]] private (
  val cluster: ClusterStorage[F],
  val node: NodeStorage[F],
  val session: SessionStorage[F],
  val rumor: RumorStorage[F],
  val trust: TrustStorage[F],
  val globalSnapshot: SnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo] with LatestBalances[F],
  val fullGlobalSnapshot: SnapshotLocalFileSystemStorage[F, GlobalSnapshot],
  val incrementalGlobalSnapshotLocalFileSystemStorage: SnapshotLocalFileSystemStorage[F, GlobalIncrementalSnapshot],
  val snapshotDownload: SnapshotDownloadStorage[F]
)
