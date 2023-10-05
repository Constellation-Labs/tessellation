package org.tessellation.sdk.http.p2p.clients

import cats.effect.Async

import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.trust.{TrustLabels, TrustScores}
import org.tessellation.sdk.domain.trust.storage.OrdinalTrustMap
import org.tessellation.sdk.http.p2p.PeerResponse
import org.tessellation.sdk.http.p2p.PeerResponse.PeerResponse
import org.tessellation.security.SecurityProvider

import org.http4s.circe.CirceEntityCodec.circeEntityDecoder
import org.http4s.client.Client

trait L0TrustClient[F[_]] {
  def getCurrentTrust: PeerResponse[F, TrustScores]
  def getPreviousTrust: PeerResponse[F, OrdinalTrustMap]

  def getPreviousPeerLabels: PeerResponse[F, TrustLabels]
}

object L0TrustClient {
  def make[F[_]: Async: SecurityProvider: KryoSerializer](client: Client[F]): L0TrustClient[F] =
    new L0TrustClient[F] {

      def getCurrentTrust: PeerResponse[F, TrustScores] =
        PeerResponse[F, TrustScores]("trust/current")(client)

      def getPreviousTrust: PeerResponse[F, OrdinalTrustMap] =
        PeerResponse[F, OrdinalTrustMap]("trust/previous")(client)

      def getPreviousPeerLabels: PeerResponse[F, TrustLabels] =
        PeerResponse[F, TrustLabels]("trust/previous/peer-labels")(client)
    }
}
