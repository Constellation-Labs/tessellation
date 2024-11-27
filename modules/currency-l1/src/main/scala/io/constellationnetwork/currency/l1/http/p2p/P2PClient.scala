package io.constellationnetwork.currency.l1.http.p2p

import cats.effect.Async

import io.constellationnetwork.currency.l1.domain.dataApplication.consensus.ConsensusClient
import io.constellationnetwork.dag.l1.domain.consensus.block.http.p2p.clients.BlockConsensusClient
import io.constellationnetwork.dag.l1.http.p2p.{L0BlockOutputClient, P2PClient => DagL1P2PClient}
import io.constellationnetwork.node.shared.domain.swap.consensus.{ConsensusClient => SwapConsensusClient}
import io.constellationnetwork.node.shared.domain.tokenlock.consensus.{ConsensusClient => TokenLockConsensusClient}
import io.constellationnetwork.node.shared.http.p2p.clients._
import io.constellationnetwork.node.shared.infrastructure.gossip.p2p.GossipClient
import io.constellationnetwork.security.SecurityProvider

import org.http4s.client.Client

object P2PClient {

  def make[F[_]: Async: SecurityProvider](
    dagL1P2PClient: DagL1P2PClient[F],
    client: Client[F]
  ): P2PClient[F] =
    new P2PClient[F](
      dagL1P2PClient.sign,
      dagL1P2PClient.node,
      dagL1P2PClient.cluster,
      dagL1P2PClient.l0Cluster,
      dagL1P2PClient.l0BlockOutputClient,
      dagL1P2PClient.gossip,
      dagL1P2PClient.blockConsensus,
      dagL1P2PClient.l0GlobalSnapshot,
      ConsensusClient.make(client),
      SwapConsensusClient.make(client),
      TokenLockConsensusClient.make(client),
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
  val consensusClient: ConsensusClient[F],
  val swapConsensusClient: SwapConsensusClient[F],
  val tokenLockConsensusClient: TokenLockConsensusClient[F],
  val l0Trust: L0TrustClient[F]
)
