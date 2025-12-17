package io.constellationnetwork.dag.l0.modules

import cats.Parallel
import cats.effect.Async
import cats.effect.std.Supervisor
import cats.syntax.all._

import io.constellationnetwork.dag.l0.config.types.IncrementalConfig
import io.constellationnetwork.dag.l0.domain.snapshot.storages.SnapshotDownloadStorage
import io.constellationnetwork.dag.l0.infrastructure.snapshot.SnapshotDownloadStorage
import io.constellationnetwork.dag.l0.infrastructure.trust.storage.TrustStorage
import io.constellationnetwork.domain.seedlist.SeedlistEntry
import io.constellationnetwork.env.AppEnvironment
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.node.shared.config.types.{SharedConfig, SnapshotConfig}
import io.constellationnetwork.node.shared.domain.cluster.storage.{ClusterStorage, SessionStorage}
import io.constellationnetwork.node.shared.domain.collateral.LatestBalances
import io.constellationnetwork.node.shared.domain.node.NodeStorage
import io.constellationnetwork.node.shared.domain.snapshot.storage.SnapshotStorage
import io.constellationnetwork.node.shared.domain.trust.storage.TrustStorage
import io.constellationnetwork.node.shared.infrastructure.gossip.RumorStorage
import io.constellationnetwork.node.shared.infrastructure.snapshot.storage._
import io.constellationnetwork.node.shared.modules.SharedStorages
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.trust.PeerObservationAdjustmentUpdateBatch
import io.constellationnetwork.security.{HashSelect, HasherSelector}

import fs2.io.file.Files

object Storages {

  def make[F[+_]: Async: Parallel: KryoSerializer: JsonSerializer: HasherSelector: Supervisor: Files](
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
      incrementalGlobalSnapshotV2PersistedLocalFileSystemStorage <- GlobalIncrementalSnapshotV2LocalFileSystemStorage.make[F](
        snapshotConfig.incrementalPersistedSnapshotPath
      )
      fullGlobalSnapshotLocalFileSystemStorage <- GlobalSnapshotLocalFileSystemStorage.make[F](
        snapshotConfig.snapshotPath
      )
      incrementalGlobalSnapshotInfoLocalFileSystemStorage <- GlobalSnapshotInfoLocalFileSystemStorage.make[F](
        snapshotConfig.snapshotInfoPath
      )
      incrementalGlobalSnapshotInfoV3LocalFileSystemStorage <- GlobalSnapshotInfoKryoLocalFileSystemStorage.make[F](
        snapshotConfig.snapshotInfoPath
      )
      incrementalKryoGlobalSnapshotInfoLocalFileSystemStorage <- GlobalSnapshotInfoV2KryoLocalFileSystemStorage.make[F](
        snapshotConfig.snapshotInfoPath
      )

      combinedGlobalSnapshotCheckpointStorage <- CombinedSnapshotCheckpointFileSystemStorage
        .make[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo](
          snapshotConfig.combinedSnapshotCheckpointPath
        )
      globalSnapshotStorage <- SnapshotStorage.make[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo](
        incrementalGlobalSnapshotPersistedLocalFileSystemStorage,
        incrementalGlobalSnapshotInfoLocalFileSystemStorage,
        snapshotConfig.inMemoryCapacity,
        incrementalConfig.lastFullGlobalSnapshotOrdinal.getOrElse(environment, SnapshotOrdinal.MinValue),
        HasherSelector[F],
        combinedGlobalSnapshotCheckpointStorage
      )
      snapshotDownloadStorage = SnapshotDownloadStorage
        .make[F](
          incrementalGlobalSnapshotTmpLocalFileSystemStorage,
          incrementalGlobalSnapshotPersistedLocalFileSystemStorage,
          fullGlobalSnapshotLocalFileSystemStorage,
          incrementalGlobalSnapshotInfoLocalFileSystemStorage,
          incrementalKryoGlobalSnapshotInfoLocalFileSystemStorage,
          incrementalGlobalSnapshotV2PersistedLocalFileSystemStorage,
          incrementalGlobalSnapshotInfoV3LocalFileSystemStorage,
          combinedGlobalSnapshotCheckpointStorage,
          hashSelect,
          incrementalConfig.lastFullGlobalSnapshotOrdinal.getOrElse(environment, SnapshotOrdinal.MinValue),
          incrementalConfig.lastV2IncrementalOrdinal.getOrElse(environment, SnapshotOrdinal.MinValue)
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
        incrementalGlobalSnapshotV2LocalFileSystemStorage = incrementalGlobalSnapshotV2PersistedLocalFileSystemStorage,
        snapshotDownload = snapshotDownloadStorage,
        globalSnapshotInfoLocalFileSystemStorage = incrementalGlobalSnapshotInfoLocalFileSystemStorage,
        globalSnapshotInfoV3LocalFileSystemStorage = incrementalGlobalSnapshotInfoV3LocalFileSystemStorage,
        globalSnapshotInfoLocalFileSystemKryoStorage = incrementalKryoGlobalSnapshotInfoLocalFileSystemStorage,
        combinedGlobalSnapshotCheckpointStorage = combinedGlobalSnapshotCheckpointStorage
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
  val incrementalGlobalSnapshotV2LocalFileSystemStorage: SnapshotLocalFileSystemStorage[F, GlobalIncrementalSnapshotV2],
  val snapshotDownload: SnapshotDownloadStorage[F],
  val globalSnapshotInfoLocalFileSystemStorage: SnapshotInfoLocalFileSystemStorage[F, GlobalSnapshotStateProof, GlobalSnapshotInfo],
  val globalSnapshotInfoV3LocalFileSystemStorage: SnapshotInfoLocalFileSystemStorage[F, GlobalSnapshotStateProofV2, GlobalSnapshotInfoV3],
  val globalSnapshotInfoLocalFileSystemKryoStorage: SnapshotInfoLocalFileSystemStorage[F, GlobalSnapshotStateProofV2, GlobalSnapshotInfoV2],
  val combinedGlobalSnapshotCheckpointStorage: CombinedSnapshotCheckpointFileSystemStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo]
)
