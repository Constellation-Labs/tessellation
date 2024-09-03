package io.constellationnetwork.node.shared.http.p2p

import cats.effect.Async

import io.constellationnetwork.node.shared.domain.cluster.services.Session
import io.constellationnetwork.node.shared.http.p2p.clients._
import io.constellationnetwork.node.shared.infrastructure.gossip.p2p.GossipClient
import io.constellationnetwork.security.SecurityProvider

import org.http4s.client._

object SharedP2PClient {

  def make[F[_]: Async: SecurityProvider](client: Client[F], session: Session[F]): SharedP2PClient[F] =
    new SharedP2PClient[F](
      SignClient.make[F](client),
      ClusterClient.make[F](client, session),
      GossipClient.make[F](client, session),
      NodeClient.make[F](client, session),
      TrustClient.make[F](client, session)
    ) {}

}

sealed abstract class SharedP2PClient[F[_]] private (
  val sign: SignClient[F],
  val cluster: ClusterClient[F],
  val gossip: GossipClient[F],
  val node: NodeClient[F],
  val trust: TrustClient[F]
)
