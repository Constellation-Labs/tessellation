package io.constellationnetwork.node.shared.modules

import cats.effect.kernel.Async
import cats.syntax.flatMap._
import cats.syntax.functor._

import io.constellationnetwork.node.shared.config.types.SharedConfig
import io.constellationnetwork.node.shared.domain.block.processing.BlockRejectionReason
import io.constellationnetwork.node.shared.domain.cluster.storage.{ClusterStorage, SessionStorage}
import io.constellationnetwork.node.shared.domain.fork.ForkInfoStorage
import io.constellationnetwork.node.shared.domain.node.NodeStorage
import io.constellationnetwork.node.shared.infrastructure.cluster.storage.{ClusterStorage, SessionStorage}
import io.constellationnetwork.node.shared.infrastructure.consensus.{CurrencySnapshotEventValidationErrorStorage, ValidationErrorStorage}
import io.constellationnetwork.node.shared.infrastructure.fork.ForkInfoStorage
import io.constellationnetwork.node.shared.infrastructure.gossip.RumorStorage
import io.constellationnetwork.node.shared.infrastructure.node.NodeStorage
import io.constellationnetwork.node.shared.snapshot.currency.CurrencySnapshotEvent
import io.constellationnetwork.schema.cluster.ClusterId
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.node.shared.domain.seedlist.SeedlistEntry

object SharedStorages {

  def make[F[_]: Async: Hasher](
    clusterId: ClusterId,
    cfg: SharedConfig,
    prioritySeedlist: Option[Set[SeedlistEntry]]
  ): F[SharedStorages[F]] =
    for {
      clusterStorage <- ClusterStorage.make[F](clusterId, prioritySeedlist)
      nodeStorage <- NodeStorage.make[F]
      sessionStorage <- SessionStorage.make[F]
      rumorStorage <- RumorStorage.make[F](cfg.gossip.storage)
      forkInfoStorage <- ForkInfoStorage.make[F](cfg.forkInfoStorage)
      currencySnapshotEventValidationErrorStorage <- CurrencySnapshotEventValidationErrorStorage.make(cfg.validationErrorStorage.maxSize)
    } yield
      new SharedStorages[F](
        cluster = clusterStorage,
        node = nodeStorage,
        session = sessionStorage,
        rumor = rumorStorage,
        forkInfo = forkInfoStorage,
        currencySnapshotEventValidationError = currencySnapshotEventValidationErrorStorage
      ) {}
}

sealed abstract class SharedStorages[F[_]] private (
  val cluster: ClusterStorage[F],
  val node: NodeStorage[F],
  val session: SessionStorage[F],
  val rumor: RumorStorage[F],
  val forkInfo: ForkInfoStorage[F],
  val currencySnapshotEventValidationError: ValidationErrorStorage[F, CurrencySnapshotEvent, BlockRejectionReason]
)
