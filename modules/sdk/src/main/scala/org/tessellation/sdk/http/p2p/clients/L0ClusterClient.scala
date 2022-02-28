package org.tessellation.sdk.http.p2p.clients

import cats.effect.Async

import org.tessellation.schema.peer.Peer
import org.tessellation.sdk.http.p2p.PeerResponse
import org.tessellation.sdk.http.p2p.PeerResponse.PeerResponse
import org.tessellation.security.SecurityProvider

import org.http4s.circe.CirceEntityCodec.circeEntityDecoder
import org.http4s.client.Client

trait L0ClusterClient[F[_]] {
  def getPeers: PeerResponse[F, Set[Peer]]
}

object L0ClusterClient {

  def make[F[_]: Async: SecurityProvider](client: Client[F]): L0ClusterClient[F] =
    new L0ClusterClient[F] {

      def getPeers: PeerResponse[F, Set[Peer]] =
        PeerResponse[F, Set[Peer]]("cluster/info")(client)
    }
}
