package org.tessellation.sdk.http.p2p

import cats.effect.Async

import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.security.SecurityProvider
import org.tessellation.sdk.domain.cluster.services.Session
import org.tessellation.sdk.http.p2p.clients.{ClusterClient, NodeClient, SignClient}
import org.tessellation.sdk.infrastructure.gossip.p2p.GossipClient

import org.http4s.client._

object SdkP2PClient {

  def make[F[_]: Async: SecurityProvider: KryoSerializer](client: Client[F], session: Session[F]): SdkP2PClient[F] =
    new SdkP2PClient[F](
      SignClient.make[F](client),
      ClusterClient.make[F](client, session),
      GossipClient.make[F](client, session),
      NodeClient.make[F](client, session)
    ) {}

}

sealed abstract class SdkP2PClient[F[_]] private (
  val sign: SignClient[F],
  val cluster: ClusterClient[F],
  val gossip: GossipClient[F],
  val node: NodeClient[F]
)
