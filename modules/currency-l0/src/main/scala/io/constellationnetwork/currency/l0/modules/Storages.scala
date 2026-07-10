package io.constellationnetwork.currency.l0.modules

import cats.Parallel
import cats.effect.kernel.Async
import cats.effect.std.{Random, Supervisor}
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.storage.{
  CalculatedStateLocalFileSystemStorage,
  GlobalSnapshotsWithStateDeltasLocalFileSystemStorage,
  GlobalSnapshotsWithStateLocalFileSystemStorage
}
import io.constellationnetwork.currency.dataApplication.{BaseDataApplicationL0Service, DataTransaction}
import io.constellationnetwork.currency.l0.domain.snapshot.storages.CurrencySnapshotCleanupStorage
import io.constellationnetwork.currency.l0.infrastructure.mempool.CurrencyEventMempool
import io.constellationnetwork.currency.l0.infrastructure.snapshot.CurrencySnapshotCleanupStorage
import io.constellationnetwork.currency.l0.snapshot.DataTransactionCodecs
import io.constellationnetwork.currency.schema.CurrencyStateKey
import io.constellationnetwork.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencySnapshotInfo}
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.node.shared.config.types.{SharedConfig, SnapshotConfig}
import io.constellationnetwork.node.shared.domain.block.processing.BlockRejectionReason
import io.constellationnetwork.node.shared.domain.cluster.storage.{ClusterStorage, L0ClusterStorage, SessionStorage}
import io.constellationnetwork.node.shared.domain.collateral.LatestBalances
import io.constellationnetwork.node.shared.domain.node.NodeStorage
import io.constellationnetwork.node.shared.domain.snapshot.storage.{LastSyncGlobalSnapshotStorage, SnapshotStorage}
import io.constellationnetwork.node.shared.infrastructure.cluster.storage.L0ClusterStorage
import io.constellationnetwork.node.shared.infrastructure.consensus.ValidationErrorStorage
import io.constellationnetwork.node.shared.infrastructure.gossip.RumorStorage
import io.constellationnetwork.node.shared.infrastructure.mempool.EventMempool
import io.constellationnetwork.node.shared.infrastructure.snapshot.storage._
import io.constellationnetwork.node.shared.modules.SharedStorages
import io.constellationnetwork.node.shared.snapshot.currency.CurrencySnapshotEvent
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.peer.L0Peer
import io.constellationnetwork.security.{Hasher, HasherSelector}

import fs2.io.file.Files
import io.circe.{Encoder => CirceEncoder}

object Storages {

  def make[F[+_]: Async: Parallel: KryoSerializer: JsonSerializer: Supervisor: Random: Files](
    sharedCfg: SharedConfig,
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
      combinedCurrencySnapshotCheckpointStorage <- CombinedSnapshotCheckpointFileSystemStorage
        .make[F, CurrencyIncrementalSnapshot, CurrencySnapshotInfo](
          snapshotConfig.combinedSnapshotCheckpointPath
        )
      snapshotStorage <- SnapshotStorage
        .make[F, CurrencyIncrementalSnapshot, CurrencySnapshotInfo](
          snapshotLocalFileSystemStorage,
          snapshotInfoLocalFileSystemStorage,
          snapshotConfig.inMemoryCapacity,
          SnapshotOrdinal.MinValue,
          hasherSelector,
          combinedCurrencySnapshotCheckpointStorage
        )
      globalSnapshotsWithStateFileStorage <- GlobalSnapshotsWithStateLocalFileSystemStorage
        .make[F](snapshotConfig.globalSnapshotsWithStatePath, snapshotConfig.maxGlobalSnapshotsWithStateStored)
      globalSnapshotsWithStateDeltasFileStorage <- GlobalSnapshotsWithStateDeltasLocalFileSystemStorage
        .make[F](snapshotConfig.globalSnapshotsWithStateDeltasPath, snapshotConfig.maxGlobalSnapshotsWithStateDeltasStored)

      lastGlobalSnapshotStorage <- LastSyncGlobalSnapshotStorage
        .make[F](
          sharedCfg.lastGlobalSnapshotsSync,
          snapshotStorage,
          globalSnapshotsWithStateFileStorage,
          globalSnapshotsWithStateDeltasFileStorage
        )
      globalL0ClusterStorage <- L0ClusterStorage.make[F](globalL0Peer)
      identifierStorage <- IdentifierStorage.make[F]
      maybeCalculatedStateStorage <- dataApplication.traverse { _ =>
        CalculatedStateLocalFileSystemStorage.make[F](snapshotConfig.calculatedStatePath)
      }
      lastGlobalSnapshotSyncStorage <- hasherSelector.withCurrent(implicit hs => LastSentGlobalSnapshotSyncStorage.make())
      currencySnapshotCleanupStorage = CurrencySnapshotCleanupStorage
        .make[F](
          snapshotLocalFileSystemStorage,
          snapshotInfoLocalFileSystemStorage
        )

      eventMempool <- {
        implicit val dtEncoder: CirceEncoder[DataTransaction] = DataTransactionCodecs.encoder(dataApplication)
        implicit val hasherInstance: Hasher[F] = hasherSelector.getCurrent
        CurrencyEventMempool.make[F](CurrencyEventMempool.defaultConfig)
      }
    } yield
      new Storages[F](
        globalL0Cluster = globalL0ClusterStorage,
        cluster = sharedStorages.cluster,
        node = sharedStorages.node,
        session = sharedStorages.session,
        rumor = sharedStorages.rumor,
        snapshot = snapshotStorage,
        lastSyncGlobalSnapshot = lastGlobalSnapshotStorage,
        incrementalSnapshotLocalFileSystemStorage = snapshotLocalFileSystemStorage,
        identifier = identifierStorage,
        calculatedStateStorage = maybeCalculatedStateStorage,
        globalSnapshotsWithStateFileStorage = globalSnapshotsWithStateFileStorage,
        globalSnapshotsWithStateDeltasFileStorage = globalSnapshotsWithStateDeltasFileStorage,
        lastGlobalSnapshotSync = lastGlobalSnapshotSyncStorage,
        currencySnapshotEventValidationError = sharedStorages.currencySnapshotEventValidationError,
        currencySnapshotCleanup = currencySnapshotCleanupStorage,
        combinedCurrencySnapshotCheckpointStorage = combinedCurrencySnapshotCheckpointStorage,
        eventMempool = eventMempool
      ) {}
}

sealed abstract class Storages[F[_]] private (
  val globalL0Cluster: L0ClusterStorage[F],
  val cluster: ClusterStorage[F],
  val node: NodeStorage[F],
  val session: SessionStorage[F],
  val rumor: RumorStorage[F],
  val snapshot: SnapshotStorage[F, CurrencyIncrementalSnapshot, CurrencySnapshotInfo] with LatestBalances[F],
  val lastSyncGlobalSnapshot: LastSyncGlobalSnapshotStorage[F],
  val incrementalSnapshotLocalFileSystemStorage: SnapshotLocalFileSystemStorage[F, CurrencyIncrementalSnapshot],
  val identifier: IdentifierStorage[F],
  val calculatedStateStorage: Option[CalculatedStateLocalFileSystemStorage[F]],
  val lastGlobalSnapshotSync: LastSentGlobalSnapshotSyncStorage[F],
  val currencySnapshotEventValidationError: ValidationErrorStorage[F, CurrencySnapshotEvent, BlockRejectionReason],
  val currencySnapshotCleanup: CurrencySnapshotCleanupStorage[F],
  val globalSnapshotsWithStateFileStorage: GlobalSnapshotsWithStateLocalFileSystemStorage[F],
  val globalSnapshotsWithStateDeltasFileStorage: GlobalSnapshotsWithStateDeltasLocalFileSystemStorage[F],
  val combinedCurrencySnapshotCheckpointStorage: CombinedSnapshotCheckpointFileSystemStorage[
    F,
    CurrencyIncrementalSnapshot,
    CurrencySnapshotInfo
  ],
  val eventMempool: EventMempool[F, CurrencySnapshotEvent, CurrencyStateKey]
)
