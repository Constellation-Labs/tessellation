package org.tessellation.modules

import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tessellation.config.types.AppConfig
import org.tessellation.infrastructure.trust.programs.TrustPush
import org.tessellation.http.p2p.P2PClient
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.domain.cluster.programs.{Joining, PeerDiscovery}
import org.tessellation.security.SecurityProvider

object Programs {

  def make[F[_]: Async: SecurityProvider: KryoSerializer](
    cfg: AppConfig,
    storages: Storages[F],
    services: Services[F],
    p2pClient: P2PClient[F],
    nodeId: PeerId
  ): F[Programs[F]] =
    for {
      pd <- PeerDiscovery.make(p2pClient.cluster, storages.cluster, nodeId)
      joining <- Joining.make(
        cfg.environment,
        storages.node,
        storages.cluster,
        p2pClient.sign,
        services.cluster,
        services.session,
        storages.session,
        nodeId,
        pd
      )
      trustPush = TrustPush.make(storages.trust, services.gossip)
    } yield new Programs[F](pd, joining, trustPush) {}
}

sealed abstract class Programs[F[_]: Async: SecurityProvider: KryoSerializer] private (
  val peerDiscovery: PeerDiscovery[F],
  val joining: Joining[F],
  val trustPush: TrustPush[F]
)
