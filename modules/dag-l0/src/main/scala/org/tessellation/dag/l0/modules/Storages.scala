package org.tessellation.dag.l0.modules

import cats.effect.Async
import cats.effect.std.Supervisor
import cats.syntax.all._

import org.tessellation.dag.l0.config.types.IncrementalConfig
import org.tessellation.dag.l0.domain.snapshot.storages.SnapshotDownloadStorage
import org.tessellation.dag.l0.infrastructure.snapshot.SnapshotDownloadStorage
import org.tessellation.dag.l0.infrastructure.trust.storage.TrustStorage
import org.tessellation.env.AppEnvironment
import org.tessellation.json.JsonSerializer
import org.tessellation.kryo.KryoSerializer
import org.tessellation.node.shared.config.types.{SharedConfig, SnapshotConfig}
import org.tessellation.node.shared.domain.cluster.storage.{ClusterStorage, SessionStorage}
import org.tessellation.node.shared.domain.collateral.LatestBalances
import org.tessellation.node.shared.domain.node.NodeStorage
import org.tessellation.node.shared.domain.seedlist.SeedlistEntry
import org.tessellation.node.shared.domain.snapshot.storage.SnapshotStorage
import org.tessellation.node.shared.domain.trust.storage.TrustStorage
import org.tessellation.node.shared.infrastructure.gossip.RumorStorage
import org.tessellation.node.shared.infrastructure.snapshot.storage._
import org.tessellation.node.shared.modules.SharedStorages
import org.tessellation.schema._
import org.tessellation.schema.trust.PeerObservationAdjustmentUpdateBatch
import org.tessellation.security.{HashSelect, HasherSelector}

object Storages {

  def make[F[+_]: Async: KryoSerializer: JsonSerializer: HasherSelector: Supervisor](
    sharedStorages: SharedStorages[F],
    sharedConfig: SharedConfig,
    seedlist: Option[Set[SeedlistEntry]],
    snapshotConfig: SnapshotConfig,
    incrementalConfig: IncrementalConfig,
    trustUpdates: Option[PeerObservationAdjustmentUpdateBatch],
    environment: AppEnvironment,
    hashSelect: HashSelect
  ): F[Storages[F]] =
    for {
      trustStorage <- TrustStorage.make[F](trustUpdates, sharedConfig.trustStorage, seedlist.map(_.map(_.peerId)))
      incrementalGlobalSnapshotTmpLocalFileSystemStorage <- GlobalIncrementalSnapshotLocalFileSystemStorage.make[F](
        snapshotConfig.incrementalTmpSnapshotPath
      )
      incrementalGlobalSnapshotPersistedLocalFileSystemStorage <- GlobalIncrementalSnapshotLocalFileSystemStorage.make[F](
        snapshotConfig.incrementalPersistedSnapshotPath
      )
      fullGlobalSnapshotLocalFileSystemStorage <- GlobalSnapshotLocalFileSystemStorage.make[F](
        snapshotConfig.snapshotPath
      )
      incrementalGlobalSnapshotInfoLocalFileSystemStorage <- GlobalSnapshotInfoLocalFileSystemStorage.make[F](
        snapshotConfig.snapshotInfoPath
      )
      incrementalKryoGlobalSnapshotInfoLocalFileSystemStorage <- GlobalSnapshotInfoKryoLocalFileSystemStorage.make[F](
        snapshotConfig.snapshotInfoPath
      )

      globalSnapshotStorage <- SnapshotStorage.make[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo](
        incrementalGlobalSnapshotPersistedLocalFileSystemStorage,
        incrementalGlobalSnapshotInfoLocalFileSystemStorage,
        snapshotConfig.inMemoryCapacity,
        incrementalConfig.lastFullGlobalSnapshotOrdinal.getOrElse(environment, SnapshotOrdinal.MinValue),
        HasherSelector[F]
      )
      snapshotDownloadStorage = SnapshotDownloadStorage
        .make[F](
          incrementalGlobalSnapshotTmpLocalFileSystemStorage,
          incrementalGlobalSnapshotPersistedLocalFileSystemStorage,
          fullGlobalSnapshotLocalFileSystemStorage,
          incrementalGlobalSnapshotInfoLocalFileSystemStorage,
          incrementalKryoGlobalSnapshotInfoLocalFileSystemStorage,
          hashSelect
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
        globalSnapshotInfoLocalFileSystemStorage = incrementalGlobalSnapshotInfoLocalFileSystemStorage,
        globalSnapshotInfoLocalFileSystemKryoStorage = incrementalKryoGlobalSnapshotInfoLocalFileSystemStorage
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
  val globalSnapshotInfoLocalFileSystemStorage: SnapshotInfoLocalFileSystemStorage[F, GlobalSnapshotStateProof, GlobalSnapshotInfo],
  val globalSnapshotInfoLocalFileSystemKryoStorage: SnapshotInfoLocalFileSystemStorage[F, GlobalSnapshotStateProof, GlobalSnapshotInfoV2]
)
