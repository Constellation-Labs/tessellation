package org.tessellation.currency.l0.modules

import cats.effect.kernel.Async
import cats.effect.std.{Random, Supervisor}
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tessellation.currency.l0.node.IdentifierStorage
import org.tessellation.currency.l0.snapshot.storages.LastBinaryHashStorage
import org.tessellation.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencySnapshotInfo}
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.peer.L0Peer
import org.tessellation.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo}
import org.tessellation.sdk.config.types.SnapshotConfig
import org.tessellation.sdk.domain.cluster.storage.{ClusterStorage, L0ClusterStorage, SessionStorage}
import org.tessellation.sdk.domain.collateral.LatestBalances
import org.tessellation.sdk.domain.node.NodeStorage
import org.tessellation.sdk.domain.snapshot.storage.{LastSnapshotStorage, SnapshotStorage}
import org.tessellation.sdk.infrastructure.cluster.storage.L0ClusterStorage
import org.tessellation.sdk.infrastructure.gossip.RumorStorage
import org.tessellation.sdk.infrastructure.snapshot.storage.{LastSnapshotStorage, SnapshotLocalFileSystemStorage, SnapshotStorage}
import org.tessellation.sdk.modules.SdkStorages

object Storages {

  def make[F[_]: Async: KryoSerializer: Supervisor: Random](
    sdkStorages: SdkStorages[F],
    snapshotConfig: SnapshotConfig,
    globalL0Peer: L0Peer
  ): F[Storages[F]] =
    for {
      snapshotLocalFileSystemStorage <- SnapshotLocalFileSystemStorage.make[F, CurrencyIncrementalSnapshot](
        snapshotConfig.incrementalPersistedSnapshotPath
      )
      snapshotStorage <- SnapshotStorage
        .make[F, CurrencyIncrementalSnapshot, CurrencySnapshotInfo](snapshotLocalFileSystemStorage, snapshotConfig.inMemoryCapacity)
      lastGlobalSnapshotStorage <- LastSnapshotStorage.make[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo]
      globalL0ClusterStorage <- L0ClusterStorage.make[F](globalL0Peer)
      lastBinaryHashStorage <- LastBinaryHashStorage.make[F]
      identifierStorage <- IdentifierStorage.make[F]
    } yield
      new Storages[F](
        globalL0Cluster = globalL0ClusterStorage,
        cluster = sdkStorages.cluster,
        node = sdkStorages.node,
        session = sdkStorages.session,
        rumor = sdkStorages.rumor,
        lastBinaryHash = lastBinaryHashStorage,
        snapshot = snapshotStorage,
        lastGlobalSnapshot = lastGlobalSnapshotStorage,
        incrementalSnapshotLocalFileSystemStorage = snapshotLocalFileSystemStorage,
        identifierStorage = identifierStorage
      ) {}
}

sealed abstract class Storages[F[_]] private (
  val globalL0Cluster: L0ClusterStorage[F],
  val cluster: ClusterStorage[F],
  val node: NodeStorage[F],
  val session: SessionStorage[F],
  val rumor: RumorStorage[F],
  val lastBinaryHash: LastBinaryHashStorage[F],
  val snapshot: SnapshotStorage[F, CurrencyIncrementalSnapshot, CurrencySnapshotInfo] with LatestBalances[F],
  val lastGlobalSnapshot: LastSnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo],
  val incrementalSnapshotLocalFileSystemStorage: SnapshotLocalFileSystemStorage[F, CurrencyIncrementalSnapshot],
  val identifierStorage: IdentifierStorage[F]
)
