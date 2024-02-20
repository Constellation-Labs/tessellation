package org.tessellation.node.shared.modules

import cats.effect.kernel.Async
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tessellation.node.shared.config.types.SharedConfig
import org.tessellation.node.shared.domain.cluster.storage.{ClusterStorage, SessionStorage}
import org.tessellation.node.shared.domain.fork.ForkInfoStorage
import org.tessellation.node.shared.domain.node.NodeStorage
import org.tessellation.node.shared.infrastructure.cluster.storage.{ClusterStorage, SessionStorage}
import org.tessellation.node.shared.infrastructure.fork.ForkInfoStorage
import org.tessellation.node.shared.infrastructure.gossip.RumorStorage
import org.tessellation.node.shared.infrastructure.node.NodeStorage
import org.tessellation.schema.cluster.ClusterId

object SharedStorages {

  def make[F[_]: Async](
    clusterId: ClusterId,
    cfg: SharedConfig
  ): F[SharedStorages[F]] =
    for {
      clusterStorage <- ClusterStorage.make[F](clusterId)
      nodeStorage <- NodeStorage.make[F]
      sessionStorage <- SessionStorage.make[F]
      rumorStorage <- RumorStorage.make[F](cfg.gossip.storage)
      forkInfoStorage <- ForkInfoStorage.make[F](cfg.forkInfoStorage)
    } yield
      new SharedStorages[F](
        cluster = clusterStorage,
        node = nodeStorage,
        session = sessionStorage,
        rumor = rumorStorage,
        forkInfo = forkInfoStorage
      ) {}
}

sealed abstract class SharedStorages[F[_]] private (
  val cluster: ClusterStorage[F],
  val node: NodeStorage[F],
  val session: SessionStorage[F],
  val rumor: RumorStorage[F],
  val forkInfo: ForkInfoStorage[F]
)
