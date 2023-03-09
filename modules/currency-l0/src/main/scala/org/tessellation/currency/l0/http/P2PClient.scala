package org.tessellation.currency.l0.http

import java.security.KeyPair

import cats.effect.Async

import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.address.Address
import org.tessellation.sdk.http.p2p.SdkP2PClient
import org.tessellation.sdk.http.p2p.clients._
import org.tessellation.sdk.infrastructure.gossip.p2p.GossipClient
import org.tessellation.security.SecurityProvider

import org.http4s.client.Client

object P2PClient {

  def make[F[_]: Async: SecurityProvider: KryoSerializer](
    sdkP2PClient: SdkP2PClient[F],
    client: Client[F],
    keyPair: KeyPair,
    identifier: Address
  ): P2PClient[F] =
    new P2PClient[F](
      L0ClusterClient.make(client),
      sdkP2PClient.cluster,
      sdkP2PClient.gossip,
      sdkP2PClient.node,
      StateChannelSnapshotClient.make(client, identifier),
      L0GlobalSnapshotClient.make[F](client)
    ) {}
}

sealed abstract class P2PClient[F[_]] private (
  val globalL0Cluster: L0ClusterClient[F],
  val cluster: ClusterClient[F],
  val gossip: GossipClient[F],
  val node: NodeClient[F],
  val stateChannelSnapshotClient: StateChannelSnapshotClient[F],
  val globalSnapshot: L0GlobalSnapshotClient[F]
)
