package org.tessellation.modules

import cats.Parallel
import cats.effect.Async
import cats.effect.std.Random
import cats.syntax.functor._
import cats.syntax.traverse._

import org.tessellation.config.types.AppConfig
import org.tessellation.http.p2p.P2PClient
import org.tessellation.infrastructure.trust.TrustDaemon
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.domain.Daemon
import org.tessellation.sdk.infrastructure.cluster.daemon.NodeStateDaemon
import org.tessellation.sdk.infrastructure.gossip.{GossipDaemon, RumorHandler}
import org.tessellation.security.SecurityProvider

object Daemons {

  def start[F[_]: Async: SecurityProvider: KryoSerializer: Random: Parallel](
    storages: Storages[F],
    services: Services[F],
    queues: Queues[F],
    p2pClient: P2PClient[F],
    handler: RumorHandler[F],
    nodeId: PeerId,
    cfg: AppConfig
  ): F[Unit] =
    List[Daemon[F]](
      GossipDaemon
        .make[F](
          storages.rumor,
          queues.rumor,
          storages.cluster,
          p2pClient.gossip,
          handler,
          nodeId,
          cfg.gossipConfig.daemon
        ),
      NodeStateDaemon.make(storages.node, services.gossip),
      TrustDaemon.make(cfg.trustConfig.daemon, storages.trust, nodeId)
    ).traverse(_.start).void

}
