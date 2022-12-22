package org.tessellation.http.p2p

import cats.effect.Async

import org.tessellation.http.p2p.clients._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.security.SecurityProvider
import org.tessellation.sdk.domain.cluster.services.Session
import org.tessellation.sdk.http.p2p.SdkP2PClient
import org.tessellation.sdk.http.p2p.clients.{ClusterClient, NodeClient, SignClient}
import org.tessellation.sdk.infrastructure.gossip.p2p.GossipClient

import org.http4s.client._

object P2PClient {

  def make[F[_]: Async: SecurityProvider: KryoSerializer](
    sdkP2PClient: SdkP2PClient[F],
    client: Client[F],
    session: Session[F]
  ): P2PClient[F] =
    new P2PClient[F](
      sdkP2PClient.sign,
      sdkP2PClient.cluster,
      sdkP2PClient.gossip,
      sdkP2PClient.node,
      TrustClient.make[F](client, session),
      GlobalSnapshotClient.make[F](client, session)
    ) {}
}

sealed abstract class P2PClient[F[_]] private (
  val sign: SignClient[F],
  val cluster: ClusterClient[F],
  val gossip: GossipClient[F],
  val node: NodeClient[F],
  val trust: TrustClient[F],
  val globalSnapshot: GlobalSnapshotClient[F]
)
