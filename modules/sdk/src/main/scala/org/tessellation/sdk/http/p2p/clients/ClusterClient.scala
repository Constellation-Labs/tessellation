package org.tessellation.sdk.http.p2p.clients

import cats.effect.Async

import org.tessellation.schema.peer.Peer
import org.tessellation.schema.security.SecurityProvider
import org.tessellation.sdk.domain.cluster.services.Session
import org.tessellation.sdk.http.p2p.PeerResponse
import org.tessellation.sdk.http.p2p.PeerResponse.PeerResponse

import org.http4s.circe.CirceEntityCodec.circeEntityDecoder
import org.http4s.client.Client
import org.http4s.client.dsl.Http4sClientDsl

trait ClusterClient[F[_]] {
  def getPeers: PeerResponse[F, Set[Peer]]
  def getDiscoveryPeers: PeerResponse[F, Set[Peer]]
}

object ClusterClient {

  def make[F[_]: Async: SecurityProvider](client: Client[F], session: Session[F]): ClusterClient[F] =
    new ClusterClient[F] with Http4sClientDsl[F] {

      def getPeers: PeerResponse[F, Set[Peer]] =
        PeerResponse[F, Set[Peer]]("cluster/peers")(client, session)

      def getDiscoveryPeers: PeerResponse[F, Set[Peer]] =
        PeerResponse[F, Set[Peer]]("cluster/discovery")(client, session)
    }
}
