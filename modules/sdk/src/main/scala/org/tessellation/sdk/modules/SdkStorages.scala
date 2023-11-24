package org.tessellation.sdk.modules

import cats.effect.kernel.Async
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tessellation.schema.cluster.ClusterId
import org.tessellation.sdk.config.types.SdkConfig
import org.tessellation.sdk.domain.cluster.storage.{ClusterStorage, SessionStorage}
import org.tessellation.sdk.domain.fork.ForkInfoStorage
import org.tessellation.sdk.domain.node.NodeStorage
import org.tessellation.sdk.infrastructure.cluster.storage.{ClusterStorage, SessionStorage}
import org.tessellation.sdk.infrastructure.fork.ForkInfoStorage
import org.tessellation.sdk.infrastructure.gossip.RumorStorage
import org.tessellation.sdk.infrastructure.node.NodeStorage

object SdkStorages {

  def make[F[_]: Async](
    clusterId: ClusterId,
    cfg: SdkConfig
  ): F[SdkStorages[F]] =
    for {
      clusterStorage <- ClusterStorage.make[F](clusterId)
      nodeStorage <- NodeStorage.make[F]
      sessionStorage <- SessionStorage.make[F]
      rumorStorage <- RumorStorage.make[F](cfg.gossipConfig.storage)
      forkInfoStorage <- ForkInfoStorage.make[F](cfg.forkInfoStorage)
    } yield
      new SdkStorages[F](
        cluster = clusterStorage,
        node = nodeStorage,
        session = sessionStorage,
        rumor = rumorStorage,
        forkInfo = forkInfoStorage
      ) {}
}

sealed abstract class SdkStorages[F[_]] private (
  val cluster: ClusterStorage[F],
  val node: NodeStorage[F],
  val session: SessionStorage[F],
  val rumor: RumorStorage[F],
  val forkInfo: ForkInfoStorage[F]
)
