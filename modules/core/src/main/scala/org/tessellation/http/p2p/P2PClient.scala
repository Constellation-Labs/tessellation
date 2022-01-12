package org.tessellation.http.p2p

import cats.effect.Concurrent

import org.tessellation.http.p2p.clients._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.sdk.http.p2p.SdkP2PClient
import org.tessellation.sdk.http.p2p.clients.{ClusterClient, SignClient}
import org.tessellation.sdk.infrastructure.gossip.p2p.GossipClient

import org.http4s.client._

object P2PClient {

  def make[F[_]: Concurrent: KryoSerializer](
    sdkP2PClient: SdkP2PClient[F],
    client: Client[F]
  ): P2PClient[F] =
    new P2PClient[F](
      sdkP2PClient.sign,
      sdkP2PClient.cluster,
      sdkP2PClient.gossip,
      TrustClient.make[F](client)
    ) {}
}

sealed abstract class P2PClient[F[_]] private (
  val sign: SignClient[F],
  val cluster: ClusterClient[F],
  val gossip: GossipClient[F],
  val trust: TrustClient[F]
)
