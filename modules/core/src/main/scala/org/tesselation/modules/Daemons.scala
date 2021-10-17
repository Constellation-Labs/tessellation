package org.tesselation.modules

import cats.Parallel
import cats.effect.Async
import cats.effect.std.Random
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tesselation.config.types.AppConfig
import org.tesselation.http.p2p.P2PClient
import org.tesselation.infrastructure.gossip.{GossipDaemon, RumorHandler}
import org.tesselation.kryo.KryoSerializer

object Daemons {

  def make[F[_]: Async: KryoSerializer: Random: Parallel](
    storages: Storages[F],
    queues: Queues[F],
    p2pClient: P2PClient[F],
    handler: RumorHandler[F],
    cfg: AppConfig
  ): F[Daemons[F]] =
    for {
      _ <- Async[F].unit
      gossipDaemon = GossipDaemon
        .make[F](storages.rumor, queues.rumor, storages.cluster, p2pClient.gossip, handler, cfg.gossipConfig.daemon)
      _ <- gossipDaemon.startDaemon
    } yield new Daemons[F](gossip = gossipDaemon) {}

}

sealed abstract class Daemons[F[_]] private (
  val gossip: GossipDaemon[F]
)
