package org.tessellation.modules

import cats.effect.kernel.Async
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tessellation.config.types.AppConfig
import org.tessellation.domain.cluster.storage.{AddressStorage, ClusterStorage, SessionStorage}
import org.tessellation.domain.gossip.RumorStorage
import org.tessellation.domain.node.NodeStorage
import org.tessellation.domain.trust.storage.TrustStorage
import org.tessellation.infrastructure.cluster.storage.{AddressStorage, ClusterStorage, SessionStorage}
import org.tessellation.infrastructure.db.Database
import org.tessellation.infrastructure.gossip.RumorStorage
import org.tessellation.infrastructure.node.NodeStorage
import org.tessellation.infrastructure.trust.storage.TrustStorage

object Storages {

  def make[F[_]: Async: Database](
    cfg: AppConfig
  ): F[Storages[F]] =
    for {
      addressStorage <- AddressStorage.make[F]
      clusterStorage <- ClusterStorage.make[F]
      nodeStorage <- NodeStorage.make[F]
      sessionStorage <- SessionStorage.make[F]
      rumorStorage <- RumorStorage.make[F](cfg.gossipConfig.storage)
      trustStorage <- TrustStorage.make[F]
    } yield
      new Storages[F](
        address = addressStorage,
        cluster = clusterStorage,
        node = nodeStorage,
        session = sessionStorage,
        rumor = rumorStorage,
        trust = trustStorage
      ) {}
}

sealed abstract class Storages[F[_]] private (
  val address: AddressStorage[F],
  val cluster: ClusterStorage[F],
  val node: NodeStorage[F],
  val session: SessionStorage[F],
  val rumor: RumorStorage[F],
  val trust: TrustStorage[F]
)