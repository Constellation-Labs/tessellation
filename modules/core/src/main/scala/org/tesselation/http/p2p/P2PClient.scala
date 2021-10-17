package org.tesselation.http.p2p

import cats.effect.Concurrent

import org.tesselation.http.p2p.clients.{ClusterClient, GossipClient, SignClient}
import org.tesselation.kryo.KryoSerializer

import org.http4s.client._

trait P2PClient[F[_]] {
  val sign: SignClient[F]
  val cluster: ClusterClient[F]
  val gossip: GossipClient[F]
}

object P2PClient {

  def make[F[_]: Concurrent: KryoSerializer](
    client: Client[F]
  ): P2PClient[F] =
    new P2PClient[F] {
      val sign: SignClient[F] = SignClient.make[F](client)
      val cluster: ClusterClient[F] = ClusterClient.make[F](client)
      val gossip: GossipClient[F] = GossipClient.make[F](client)
    }
}
