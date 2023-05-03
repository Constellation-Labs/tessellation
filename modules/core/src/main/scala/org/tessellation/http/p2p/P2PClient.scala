package org.tessellation.http.p2p

import org.tessellation.sdk.http.p2p.SdkP2PClient
import org.tessellation.sdk.http.p2p.clients._
import org.tessellation.sdk.infrastructure.gossip.p2p.GossipClient

object P2PClient {

  def make[F[_]](
    sdkP2PClient: SdkP2PClient[F]
  ): P2PClient[F] =
    new P2PClient[F](
      sdkP2PClient.sign,
      sdkP2PClient.cluster,
      sdkP2PClient.gossip,
      sdkP2PClient.node,
      sdkP2PClient.l0GlobalSnapshot
    ) {}
}

sealed abstract class P2PClient[F[_]] private (
  val sign: SignClient[F],
  val cluster: ClusterClient[F],
  val gossip: GossipClient[F],
  val node: NodeClient[F],
  val l0GlobalSnapshot: L0GlobalSnapshotClient[F]
)
