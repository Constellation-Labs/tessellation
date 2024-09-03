package io.constellationnetwork.dag.l1.http.p2p

import cats.effect.Async

import io.constellationnetwork.dag.l1.domain.consensus.block.http.p2p.clients.BlockConsensusClient
import io.constellationnetwork.node.shared.http.p2p.SharedP2PClient
import io.constellationnetwork.node.shared.http.p2p.clients._
import io.constellationnetwork.node.shared.infrastructure.gossip.p2p.GossipClient
import io.constellationnetwork.security.SecurityProvider

import org.http4s.client._

object P2PClient {

  def make[F[_]: Async: SecurityProvider](
    sharedP2PClient: SharedP2PClient[F],
    client: Client[F],
    currencyPathPrefix: String
  ): P2PClient[F] =
    new P2PClient[F](
      sharedP2PClient.sign,
      sharedP2PClient.node,
      sharedP2PClient.cluster,
      L0ClusterClient.make(client),
      L0BlockOutputClient.make(currencyPathPrefix, client),
      sharedP2PClient.gossip,
      BlockConsensusClient.make(client),
      L0GlobalSnapshotClient.make(client),
      L0TrustClient.make(client)
    ) {}
}

sealed abstract class P2PClient[F[_]] private (
  val sign: SignClient[F],
  val node: NodeClient[F],
  val cluster: ClusterClient[F],
  val l0Cluster: L0ClusterClient[F],
  val l0BlockOutputClient: L0BlockOutputClient[F],
  val gossip: GossipClient[F],
  val blockConsensus: BlockConsensusClient[F],
  val l0GlobalSnapshot: L0GlobalSnapshotClient[F],
  val l0Trust: L0TrustClient[F]
)
