package org.tessellation.dag.l1.http.p2p

import cats.Eq
import cats.effect.Async

import org.tessellation.dag.l1.domain.consensus.block.http.p2p.clients.BlockConsensusClient
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.Block
import org.tessellation.schema.snapshot.{Snapshot, SnapshotInfo}
import org.tessellation.schema.transaction.Transaction
import org.tessellation.schema.{Block, GlobalSnapshot}
import org.tessellation.sdk.domain.http.p2p.SnapshotClient
import org.tessellation.sdk.http.p2p.SdkP2PClient
import org.tessellation.sdk.http.p2p.clients._
import org.tessellation.sdk.infrastructure.gossip.p2p.GossipClient
import org.tessellation.security.SecurityProvider

import io.circe.{Decoder, Encoder}
import org.http4s.client._

object P2PClient {

  def make[
    F[_]: Async: SecurityProvider: KryoSerializer,
    T <: Transaction: Encoder: Eq,
    B <: Block[T]: Encoder: Eq: Ordering,
    S <: Snapshot[T, B]: Eq: Decoder,
    SI <: SnapshotInfo
  ](
    sdkP2PClient: SdkP2PClient[F],
    client: Client[F],
    currencyPathPrefix: String
  ): P2PClient[F, T, B, S, SI] =
    new P2PClient[F, T, B, S, SI](
      sdkP2PClient.sign,
      sdkP2PClient.node,
      sdkP2PClient.cluster,
      L0ClusterClient.make(client),
      L0CurrencyClusterClient.make(currencyPathPrefix, client),
      sdkP2PClient.gossip,
      BlockConsensusClient.make(client),
      L0GlobalSnapshotClient.make[F, T, B, S, SI](client)
    ) {}
}

sealed abstract class P2PClient[F[_], T <: Transaction, B <: Block[T], S <: Snapshot[T, B], SI <: SnapshotInfo] private (
  val sign: SignClient[F],
  val node: NodeClient[F],
  val cluster: ClusterClient[F],
  val l0Cluster: L0ClusterClient[F],
  val l0CurrencyCluster: L0CurrencyClusterClient[F, B],
  val gossip: GossipClient[F],
  val blockConsensus: BlockConsensusClient[F, T],
  val l0GlobalSnapshotClient: L0GlobalSnapshotClient[F, T, B, S, SI]
)
