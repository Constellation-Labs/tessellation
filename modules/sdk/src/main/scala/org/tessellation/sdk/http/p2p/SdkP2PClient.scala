package org.tessellation.sdk.http.p2p

import cats.effect.Concurrent

import org.tessellation.kryo.KryoSerializer
import org.tessellation.sdk.http.p2p.clients.{ClusterClient, SignClient}
import org.tessellation.sdk.infrastructure.gossip.p2p.GossipClient

import org.http4s.client._

object SdkP2PClient {

  def make[F[_]: Concurrent: KryoSerializer](client: Client[F]): SdkP2PClient[F] =
    new SdkP2PClient[F](
      SignClient.make[F](client),
      ClusterClient.make[F](client),
      GossipClient.make[F](client)
    ) {}

}

sealed abstract class SdkP2PClient[F[_]] private (
  val sign: SignClient[F],
  val cluster: ClusterClient[F],
  val gossip: GossipClient[F]
)
