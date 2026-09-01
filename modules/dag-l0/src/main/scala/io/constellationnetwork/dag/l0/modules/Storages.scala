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
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.node.shared.infrastructure.snapshot.storage._
import io.constellationnetwork.node.shared.modules.SharedStorages
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.mpt.{GlobalStateKey, MptStore}
import io.constellationnetwork.schema.trust.PeerObservationAdjustmentUpdateBatch
import io.constellationnetwork.security.{HashSelect, HasherSelector}

import fs2.io.file.Files

object Storages {

  def make[F[+_]: Async: Parallel: KryoSerializer: JsonSerializer: HasherSelector: Supervisor: Files: Metrics](
    sharedStorages: SharedStorages[F],
    sharedConfig: SharedConfig,
    seedlist: Option[Set[SeedlistEntry]],
    snapshotConfig: SnapshotConfig,
    incrementalConfig: IncrementalConfig,
    trustUpdates: Option[PeerObservationAdjustmentUpdateBatch],
    environment: AppEnvironment,
    hashSelect: HashSelect,
    protectedSnapshotInfoOrdinals: Set[SnapshotOrdinal]
  )(
    implicit globalStateProofSelector: GlobalStateProofSelector
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
        combinedGlobalSnapshotCheckpointStorage,
        protectedSnapshotInfoOrdinals
      )
      snapshotDownloadStorage = SnapshotDownloadStorage
        .make[F](
          incrementalGlobalSnapshotTmpLocalFileSystemStorage,
          incrementalGlobalSnapshotPersistedLocalFileSystemStorage,
          fullGlobalSnapshotLocalFileSystemStorage,
          incrementalGlobalSnapshotInfoLocalFileSystemStorage,
          incrementalKryoGlobalSnapshotInfoLocalFileSystemStorage,
          combinedGlobalSnapshotCheckpointStorage,
          hashSelect,
          sharedStorages.mptStore,
          protectedSnapshotInfoOrdinals
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
        globalSnapshotInfoLocalFileSystemKryoStorage = incrementalKryoGlobalSnapshotInfoLocalFileSystemStorage,
        combinedGlobalSnapshotCheckpointStorage = combinedGlobalSnapshotCheckpointStorage,
        mptStore = sharedStorages.mptStore
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
  val globalSnapshotInfoLocalFileSystemKryoStorage: SnapshotInfoLocalFileSystemStorage[F, GlobalSnapshotStateProof, GlobalSnapshotInfoV2],
  val combinedGlobalSnapshotCheckpointStorage: CombinedSnapshotCheckpointFileSystemStorage[
    F,
    GlobalIncrementalSnapshot,
    GlobalSnapshotInfo
  ],
  val mptStore: MptStore[F, GlobalStateKey]
)
