package io.constellationnetwork.node.shared.modules

import cats.Parallel
import cats.effect.kernel.Async
import cats.syntax.flatMap._
import cats.syntax.functor._

import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.node.shared.config.types.SharedConfig
import io.constellationnetwork.node.shared.domain.block.processing.BlockRejectionReason
import io.constellationnetwork.node.shared.domain.cluster.storage.{ClusterStorage, SessionStorage}
import io.constellationnetwork.node.shared.domain.collateral.LatestBalances
import io.constellationnetwork.node.shared.domain.node.NodeStorage
import io.constellationnetwork.node.shared.domain.snapshot.storage.{LastNGlobalSnapshotStorage, LastSnapshotStorage}
import io.constellationnetwork.node.shared.infrastructure.cluster.storage.{ClusterStorage, SessionStorage}
import io.constellationnetwork.node.shared.infrastructure.consensus.{CurrencySnapshotEventValidationErrorStorage, ValidationErrorStorage}
import io.constellationnetwork.node.shared.infrastructure.gossip.RumorStorage
import io.constellationnetwork.node.shared.infrastructure.node.NodeStorage
import io.constellationnetwork.node.shared.infrastructure.snapshot.storage.{LastNGlobalSnapshotStorage, LastSnapshotStorage}
import io.constellationnetwork.node.shared.snapshot.currency.CurrencySnapshotEvent
import io.constellationnetwork.schema.cluster.ClusterId
import io.constellationnetwork.schema.mpt.{GlobalStateKey, MptStore}
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo}
import io.constellationnetwork.security.mpt.producer.FileSystemMerklePatriciaProducer
import io.constellationnetwork.security.{Hasher, HasherSelector, SecurityProvider}

object SharedStorages {

  def make[F[_]: Parallel: JsonSerializer: Async: Hasher: HasherSelector: SecurityProvider](
    clusterId: ClusterId,
    cfg: SharedConfig,
    globalSnapshotSignerAllowlist: Option[Set[PeerId]] = None
  ): F[SharedStorages[F]] =
    for {
      clusterStorage <- ClusterStorage.make[F](clusterId)
      nodeStorage <- NodeStorage.make[F]
      sessionStorage <- SessionStorage.make[F]
      rumorStorage <- RumorStorage.make[F](cfg.gossip.storage)
      currencySnapshotEventValidationErrorStorage <- CurrencySnapshotEventValidationErrorStorage.make(cfg.validationErrorStorage.maxSize)
      lastNGlobalSnapshotStorage <- LastNGlobalSnapshotStorage.make[F](cfg.lastGlobalSnapshotsSync, globalSnapshotSignerAllowlist)
      lastGlobalSnapshotStorage <- LastSnapshotStorage.make[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo]
      mptProducer <- FileSystemMerklePatriciaProducer.make[F](cfg.mptSnapshotInfoPath)
      mptStore <- MptStore.make[F, GlobalStateKey](
        mptProducer,
        GlobalStateKey.toHex[F]
      )
    } yield
      new SharedStorages[F](
        cluster = clusterStorage,
        node = nodeStorage,
        session = sessionStorage,
        rumor = rumorStorage,
        currencySnapshotEventValidationError = currencySnapshotEventValidationErrorStorage,
        lastNGlobalSnapshot = lastNGlobalSnapshotStorage,
        lastGlobalSnapshot = lastGlobalSnapshotStorage,
        mptStore = mptStore
      ) {}
}

sealed abstract class SharedStorages[F[_]] private (
  val cluster: ClusterStorage[F],
  val node: NodeStorage[F],
  val session: SessionStorage[F],
  val rumor: RumorStorage[F],
  val currencySnapshotEventValidationError: ValidationErrorStorage[F, CurrencySnapshotEvent, BlockRejectionReason],
  val lastNGlobalSnapshot: LastNGlobalSnapshotStorage[F],
  val lastGlobalSnapshot: LastSnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo] with LatestBalances[F],
  val mptStore: MptStore[F, GlobalStateKey]
)
