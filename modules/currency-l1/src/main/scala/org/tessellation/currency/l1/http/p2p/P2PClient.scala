package org.tessellation.currency.l1.http.p2p

import cats.effect.Async

import org.tessellation.currency.l1.domain.dataApplication.consensus.ConsensusClient
import org.tessellation.dag.l1.domain.consensus.block.http.p2p.clients.BlockConsensusClient
import org.tessellation.dag.l1.http.p2p.{L0BlockOutputClient, P2PClient => DagL1P2PClient}
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.Block
import org.tessellation.schema.transaction.Transaction
import org.tessellation.sdk.http.p2p.clients._
import org.tessellation.sdk.infrastructure.gossip.p2p.GossipClient
import org.tessellation.security.SecurityProvider

import io.circe.Encoder
import org.http4s.client.Client

object P2PClient {

  def make[
    F[_]: Async: SecurityProvider: KryoSerializer,
    T <: Transaction: Encoder,
    B <: Block[T]: Encoder
  ](
    dagL1P2PClient: DagL1P2PClient[F, T, B],
    client: Client[F]
  ): P2PClient[F, T, B] =
    new P2PClient[F, T, B](
      dagL1P2PClient.sign,
      dagL1P2PClient.node,
      dagL1P2PClient.cluster,
      dagL1P2PClient.l0Cluster,
      dagL1P2PClient.l0BlockOutputClient,
      dagL1P2PClient.gossip,
      dagL1P2PClient.blockConsensus,
      dagL1P2PClient.l0GlobalSnapshot,
      ConsensusClient.make(client)
    ) {}
}

sealed abstract class P2PClient[
  F[_],
  T <: Transaction,
  B <: Block[T]
] private (
  val sign: SignClient[F],
  val node: NodeClient[F],
  val cluster: ClusterClient[F],
  val l0Cluster: L0ClusterClient[F],
  val l0BlockOutputClient: L0BlockOutputClient[F, B],
  val gossip: GossipClient[F],
  val blockConsensus: BlockConsensusClient[F, T],
  val l0GlobalSnapshot: L0GlobalSnapshotClient[F],
  val consensusClient: ConsensusClient[F]
)
