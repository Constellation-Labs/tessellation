package io.constellationnetwork.currency.l0.http.p2p

import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.currency.l0.snapshot.CurrencySnapshotClient
import io.constellationnetwork.currency.l0.snapshot.CurrencySnapshotClient.CurrencySnapshotClient
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.node.shared.config.types.SharedConfig
import io.constellationnetwork.node.shared.domain.cluster.services.Session
import io.constellationnetwork.node.shared.http.p2p.SharedP2PClient
import io.constellationnetwork.node.shared.http.p2p.clients._
import io.constellationnetwork.node.shared.infrastructure.gossip.p2p.GossipClient
import io.constellationnetwork.security.SecurityProvider

import org.http4s.client.Client

object P2PClient {

  def make[F[_]: Async: SecurityProvider: KryoSerializer](
    sharedP2PClient: SharedP2PClient[F],
    client: Client[F],
    session: Session[F],
    sharedConfig: SharedConfig
  ): P2PClient[F] =
    new P2PClient[F](
      L0ClusterClient.make(client),
      sharedP2PClient.cluster,
      sharedP2PClient.gossip,
      sharedP2PClient.node,
      StateChannelSnapshotClient.make(client, sharedConfig.snapshotBinarySenderTimeouts),
      L0GlobalSnapshotClient.make(client, none, sharedConfig.snapshotTimeoutsConfig),
      CurrencySnapshotClient.make[F](client, session, sharedConfig.snapshotTimeoutsConfig),
      L0TrustClient.make(client),
      DataApplicationClient.make(client, session)
    ) {}
}

sealed abstract class P2PClient[F[_]] private (
  val globalL0Cluster: L0ClusterClient[F],
  val cluster: ClusterClient[F],
  val gossip: GossipClient[F],
  val node: NodeClient[F],
  val stateChannelSnapshot: StateChannelSnapshotClient[F],
  val l0GlobalSnapshot: L0GlobalSnapshotClient[F],
  val currencySnapshot: CurrencySnapshotClient[F],
  val l0Trust: L0TrustClient[F],
  val dataApplication: DataApplicationClient[F]
)
