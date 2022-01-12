package org.tessellation.sdk.modules

import java.security.KeyPair

import cats.effect.kernel.Async
import cats.syntax.functor._

import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.config.types.SdkConfig
import org.tessellation.sdk.domain.cluster.services.{Cluster, Session}
import org.tessellation.sdk.domain.gossip.Gossip
import org.tessellation.sdk.infrastructure.cluster.services.{Cluster, Session}
import org.tessellation.sdk.infrastructure.gossip.Gossip
import org.tessellation.security.SecurityProvider

object SdkServices {

  def make[F[_]: Async: KryoSerializer: SecurityProvider](
    cfg: SdkConfig,
    nodeId: PeerId,
    keyPair: KeyPair,
    storages: SdkStorages[F],
    queues: SdkQueues[F]
  ): F[SdkServices[F]] = {
    val session = Session.make[F](storages.session, storages.node)
    val cluster = Cluster
      .make[F](cfg.httpConfig, nodeId, keyPair, storages.session)

    for {
      gossip <- Gossip.make[F](queues.rumor, nodeId, keyPair)
    } yield
      new SdkServices[F](
        cluster = cluster,
        session = session,
        gossip = gossip
      ) {}
  }
}

sealed abstract class SdkServices[F[_]] private (
  val cluster: Cluster[F],
  val session: Session[F],
  val gossip: Gossip[F]
)
