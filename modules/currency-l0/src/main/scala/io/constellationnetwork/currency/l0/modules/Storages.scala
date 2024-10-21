package io.constellationnetwork.currency.l0.modules

import cats.effect.kernel.Async
import cats.effect.std.{Random, Supervisor}
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.BaseDataApplicationL0Service
import io.constellationnetwork.currency.dataApplication.storage.CalculatedStateLocalFileSystemStorage
import io.constellationnetwork.currency.l0.node.IdentifierStorage
import io.constellationnetwork.currency.l0.snapshot.storage.{LastSentGlobalSnapshotSyncStorage, LastSynchronizedGlobalSnapshotStorage}
import io.constellationnetwork.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencySnapshotInfo}
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.node.shared.config.types.SnapshotConfig
import io.constellationnetwork.node.shared.domain.cluster.storage.{ClusterStorage, L0ClusterStorage, SessionStorage}
import io.constellationnetwork.node.shared.domain.collateral.LatestBalances
import io.constellationnetwork.node.shared.domain.node.NodeStorage
import io.constellationnetwork.node.shared.domain.snapshot.storage.{LastSnapshotStorage, SnapshotStorage}
import io.constellationnetwork.node.shared.infrastructure.cluster.storage.L0ClusterStorage
import io.constellationnetwork.node.shared.infrastructure.gossip.RumorStorage
import io.constellationnetwork.node.shared.infrastructure.snapshot.storage._
import io.constellationnetwork.node.shared.modules.SharedStorages
import io.constellationnetwork.schema.peer.L0Peer
import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo, SnapshotOrdinal}
import io.constellationnetwork.security.HasherSelector

import fs2.io.file.Path

object Storages {

  def dataApplicationCalculatedStatePath = Path("data/calculated_state")

  def make[F[+_]: Async: KryoSerializer: JsonSerializer: Supervisor: Random](
    sharedStorages: SharedStorages[F],
    snapshotConfig: SnapshotConfig,
    globalL0Peer: L0Peer,
    dataApplication: Option[BaseDataApplicationL0Service[F]],
    hasherSelector: HasherSelector[F]
  ): F[Storages[F]] =
    for {
      snapshotLocalFileSystemStorage <- CurrencyIncrementalSnapshotLocalFileSystemStorage.make[F](
        snapshotConfig.incrementalPersistedSnapshotPath
      )
      snapshotInfoLocalFileSystemStorage <- CurrencySnapshotInfoLocalFileSystemStorage.make[F](snapshotConfig.snapshotInfoPath)
      snapshotStorage <- SnapshotStorage
        .make[F, CurrencyIncrementalSnapshot, CurrencySnapshotInfo](
          snapshotLocalFileSystemStorage,
          snapshotInfoLocalFileSystemStorage,
          snapshotConfig.inMemoryCapacity,
          SnapshotOrdinal.MinValue,
          hasherSelector
        )
      lastGlobalSnapshotStorage <- LastSynchronizedGlobalSnapshotStorage.make[F](snapshotStorage)
      globalL0ClusterStorage <- L0ClusterStorage.make[F](globalL0Peer)
      identifierStorage <- IdentifierStorage.make[F]
      maybeCalculatedStateStorage <- dataApplication.traverse { _ =>
        CalculatedStateLocalFileSystemStorage.make[F](dataApplicationCalculatedStatePath)
      }
      lastGlobalSnapshotSyncStorage <- hasherSelector.withCurrent(implicit hs => LastSentGlobalSnapshotSyncStorage.make())
    } yield
      new Storages[F](
        globalL0Cluster = globalL0ClusterStorage,
        cluster = sharedStorages.cluster,
        node = sharedStorages.node,
        session = sharedStorages.session,
        rumor = sharedStorages.rumor,
        snapshot = snapshotStorage,
        lastGlobalSnapshot = lastGlobalSnapshotStorage,
        incrementalSnapshotLocalFileSystemStorage = snapshotLocalFileSystemStorage,
        identifier = identifierStorage,
        calculatedStateStorage = maybeCalculatedStateStorage,
        lastGlobalSnapshotSync = lastGlobalSnapshotSyncStorage
      ) {}
}

sealed abstract class Storages[F[_]] private (
  val globalL0Cluster: L0ClusterStorage[F],
  val cluster: ClusterStorage[F],
  val node: NodeStorage[F],
  val session: SessionStorage[F],
  val rumor: RumorStorage[F],
  val snapshot: SnapshotStorage[F, CurrencyIncrementalSnapshot, CurrencySnapshotInfo] with LatestBalances[F],
  val lastGlobalSnapshot: LastSnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo]
    with LastSynchronizedGlobalSnapshotStorage[F],
  val incrementalSnapshotLocalFileSystemStorage: SnapshotLocalFileSystemStorage[F, CurrencyIncrementalSnapshot],
  val identifier: IdentifierStorage[F],
  val calculatedStateStorage: Option[CalculatedStateLocalFileSystemStorage[F]],
  val lastGlobalSnapshotSync: LastSentGlobalSnapshotSyncStorage[F]
)
