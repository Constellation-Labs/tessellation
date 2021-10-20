package org.tesselation.modules

import cats.Parallel
import cats.effect.Async
import cats.effect.std.Random
import cats.syntax.functor._
import cats.syntax.traverse._

import org.tesselation.config.types.AppConfig
import org.tesselation.domain.Daemon
import org.tesselation.http.p2p.P2PClient
import org.tesselation.infrastructure.cluster.daemon.NodeStateDaemon
import org.tesselation.infrastructure.gossip.{GossipDaemon, RumorHandler}
import org.tesselation.keytool.security.SecurityProvider
import org.tesselation.kryo.KryoSerializer
import org.tesselation.schema.peer.PeerId

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
      NodeStateDaemon.make(storages.node, services.gossip)
    ).traverse(_.start).void

}
