package org.tessellation.dag.l0.modules

import cats.effect.Async
import cats.effect.std.Supervisor
import cats.syntax.all._

import org.tessellation.dag.l0.cli.incremental
import org.tessellation.dag.l0.domain.snapshot.storages.SnapshotDownloadStorage
import org.tessellation.dag.l0.infrastructure.snapshot.SnapshotDownloadStorage
import org.tessellation.dag.l0.infrastructure.trust.storage.TrustStorage
import org.tessellation.env.AppEnvironment
import org.tessellation.kryo.KryoSerializer
import org.tessellation.node.shared.config.types.{SharedConfig, SnapshotConfig}
import org.tessellation.node.shared.domain.cluster.storage.{ClusterStorage, SessionStorage}
import org.tessellation.node.shared.domain.collateral.LatestBalances
import org.tessellation.node.shared.domain.node.NodeStorage
import org.tessellation.node.shared.domain.seedlist.SeedlistEntry
import org.tessellation.node.shared.domain.snapshot.storage.SnapshotStorage
import org.tessellation.node.shared.domain.trust.storage.TrustStorage
import org.tessellation.node.shared.infrastructure.gossip.RumorStorage
import org.tessellation.node.shared.infrastructure.snapshot.storage.{
  SnapshotInfoLocalFileSystemStorage,
  SnapshotLocalFileSystemStorage,
  SnapshotStorage
}
import org.tessellation.node.shared.modules.SharedStorages
import org.tessellation.schema._
import org.tessellation.schema.trust.PeerObservationAdjustmentUpdateBatch
import org.tessellation.security.Hasher

object Storages {

  def make[F[_]: Async: KryoSerializer: Hasher: Supervisor](
    sharedStorages: SharedStorages[F],
    sharedConfig: SharedConfig,
    seedlist: Option[Set[SeedlistEntry]],
    snapshotConfig: SnapshotConfig,
    trustUpdates: Option[PeerObservationAdjustmentUpdateBatch],
    environment: AppEnvironment
  ): F[Storages[F]] =
    for {
      trustStorage <- TrustStorage.make[F](trustUpdates, sharedConfig.trustStorage, seedlist.map(_.map(_.peerId)))
      incrementalGlobalSnapshotTmpLocalFileSystemStorage <- SnapshotLocalFileSystemStorage.make[F, GlobalIncrementalSnapshot](
        snapshotConfig.incrementalTmpSnapshotPath
      )
      incrementalGlobalSnapshotPersistedLocalFileSystemStorage <- SnapshotLocalFileSystemStorage.make[F, GlobalIncrementalSnapshot](
        snapshotConfig.incrementalPersistedSnapshotPath
      )
      fullGlobalSnapshotLocalFileSystemStorage <- SnapshotLocalFileSystemStorage.make[F, GlobalSnapshot](
        snapshotConfig.snapshotPath
      )
      incrementalGlobalSnapshotInfoLocalFileSystemStorage <- SnapshotInfoLocalFileSystemStorage
        .make[F, GlobalSnapshotStateProof, GlobalSnapshotInfo](
          snapshotConfig.snapshotInfoPath
        )
      globalSnapshotStorage <- SnapshotStorage.make[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo](
        incrementalGlobalSnapshotPersistedLocalFileSystemStorage,
        incrementalGlobalSnapshotInfoLocalFileSystemStorage,
        snapshotConfig.inMemoryCapacity,
        incremental.lastFullGlobalSnapshot.getOrElse(environment, SnapshotOrdinal.MinValue)
      )
      snapshotDownloadStorage = SnapshotDownloadStorage
        .make[F](
          incrementalGlobalSnapshotTmpLocalFileSystemStorage,
          incrementalGlobalSnapshotPersistedLocalFileSystemStorage,
          fullGlobalSnapshotLocalFileSystemStorage,
          incrementalGlobalSnapshotInfoLocalFileSystemStorage
        )
    } yield
      new Storages[F](
        cluster = sharedStorages.cluster,
        node = sharedStorages.node,
        session = sharedStorages.session,
        rumor = sharedStorages.rumor,
        trust = trustStorage,
        globalSnapshot = globalSnapshotStorage,
        fullGlobalSnapshot = fullGlobalSnapshotLocalFileSystemStorage,
        incrementalGlobalSnapshotLocalFileSystemStorage = incrementalGlobalSnapshotPersistedLocalFileSystemStorage,
        snapshotDownload = snapshotDownloadStorage,
        globalSnapshotInfoLocalFileSystemStorage = incrementalGlobalSnapshotInfoLocalFileSystemStorage
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
  val snapshotDownload: SnapshotDownloadStorage[F],
  val globalSnapshotInfoLocalFileSystemStorage: SnapshotInfoLocalFileSystemStorage[F, GlobalSnapshotStateProof, GlobalSnapshotInfo]
)
