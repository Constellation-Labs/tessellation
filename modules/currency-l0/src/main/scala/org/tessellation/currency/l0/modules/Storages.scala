package org.tessellation.currency.l0.modules

import cats.effect.kernel.Async
import cats.effect.std.{Random, Supervisor}
import cats.syntax.all._

import org.tessellation.currency.dataApplication.BaseDataApplicationL0Service
import org.tessellation.currency.dataApplication.storage.CalculatedStateLocalFileSystemStorage
import org.tessellation.currency.l0.node.IdentifierStorage
import org.tessellation.currency.schema.currency
import org.tessellation.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencySnapshotInfo}
import org.tessellation.json.JsonSerializer
import org.tessellation.kryo.KryoSerializer
import org.tessellation.node.shared.config.types.SnapshotConfig
import org.tessellation.node.shared.domain.cluster.storage.{ClusterStorage, L0ClusterStorage, SessionStorage}
import org.tessellation.node.shared.domain.collateral.LatestBalances
import org.tessellation.node.shared.domain.node.NodeStorage
import org.tessellation.node.shared.domain.snapshot.storage.{LastSnapshotStorage, SnapshotStorage}
import org.tessellation.node.shared.infrastructure.cluster.storage.L0ClusterStorage
import org.tessellation.node.shared.infrastructure.gossip.RumorStorage
import org.tessellation.node.shared.infrastructure.snapshot.storage._
import org.tessellation.node.shared.modules.SharedStorages
import org.tessellation.schema.peer.L0Peer
import org.tessellation.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo, SnapshotOrdinal}
import org.tessellation.security.Hasher

import fs2.io.file.Path

object Storages {

  def dataApplicationCalculatedStatePath = Path("data/calculated_state")

  def make[F[_]: Async: KryoSerializer: JsonSerializer: Hasher: Supervisor: Random](
    sharedStorages: SharedStorages[F],
    snapshotConfig: SnapshotConfig,
    globalL0Peer: L0Peer,
    dataApplication: Option[BaseDataApplicationL0Service[F]]
  ): F[Storages[F]] =
    for {
      snapshotLocalFileSystemStorage <- SnapshotLocalFileSystemStorage.make[F, CurrencyIncrementalSnapshot](
        snapshotConfig.incrementalPersistedSnapshotPath
      )
      snapshotInfoLocalFileSystemStorage <- SnapshotInfoLocalFileSystemStorage
        .make[F, currency.CurrencySnapshotStateProof, CurrencySnapshotInfo](
          snapshotConfig.snapshotInfoPath
        )
      snapshotStorage <- SnapshotStorage
        .make[F, CurrencyIncrementalSnapshot, CurrencySnapshotInfo](
          snapshotLocalFileSystemStorage,
          snapshotInfoLocalFileSystemStorage,
          snapshotConfig.inMemoryCapacity,
          SnapshotOrdinal.MinValue
        )
      lastGlobalSnapshotStorage <- LastSnapshotStorage.make[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo]
      globalL0ClusterStorage <- L0ClusterStorage.make[F](globalL0Peer)
      identifierStorage <- IdentifierStorage.make[F]
      maybeCalculatedStateStorage <- dataApplication.traverse { _ =>
        CalculatedStateLocalFileSystemStorage.make[F](dataApplicationCalculatedStatePath)
      }
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
        calculatedStateStorage = maybeCalculatedStateStorage
      ) {}
}

sealed abstract class Storages[F[_]] private (
  val globalL0Cluster: L0ClusterStorage[F],
  val cluster: ClusterStorage[F],
  val node: NodeStorage[F],
  val session: SessionStorage[F],
  val rumor: RumorStorage[F],
  val snapshot: SnapshotStorage[F, CurrencyIncrementalSnapshot, CurrencySnapshotInfo] with LatestBalances[F],
  val lastGlobalSnapshot: LastSnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo],
  val incrementalSnapshotLocalFileSystemStorage: SnapshotLocalFileSystemStorage[F, CurrencyIncrementalSnapshot],
  val identifier: IdentifierStorage[F],
  val calculatedStateStorage: Option[CalculatedStateLocalFileSystemStorage[F]]
)
