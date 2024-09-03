package io.constellationnetwork.dag.l0.http.p2p

import cats.effect.Async
import cats.syntax.option._

import io.constellationnetwork.node.shared.domain.cluster.services.Session
import io.constellationnetwork.node.shared.http.p2p.SharedP2PClient
import io.constellationnetwork.node.shared.http.p2p.clients._
import io.constellationnetwork.node.shared.infrastructure.gossip.p2p.GossipClient
import io.constellationnetwork.security.SecurityProvider

import org.http4s.client.Client

object P2PClient {

  def make[F[_]: Async: SecurityProvider](
    sharedP2PClient: SharedP2PClient[F],
    client: Client[F],
    session: Session[F]
  ): P2PClient[F] =
    new P2PClient[F](
      sharedP2PClient.sign,
      sharedP2PClient.cluster,
      sharedP2PClient.gossip,
      sharedP2PClient.node,
      L0GlobalSnapshotClient.make(client, session.some)
    ) {}
}

sealed abstract class P2PClient[F[_]] private (
  val sign: SignClient[F],
  val cluster: ClusterClient[F],
  val gossip: GossipClient[F],
  val node: NodeClient[F],
  val globalSnapshot: L0GlobalSnapshotClient[F]
)
