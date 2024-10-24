package io.constellationnetwork.node.shared.http.p2p.clients

import cats.effect.Async

import io.constellationnetwork.node.shared.domain.cluster.services.Session
import io.constellationnetwork.node.shared.http.p2p.PeerResponse
import io.constellationnetwork.node.shared.http.p2p.PeerResponse.PeerResponse
import io.constellationnetwork.schema.trust.PublicTrust

import org.http4s.Method.GET
import org.http4s.circe.CirceEntityCodec.circeEntityDecoder
import org.http4s.client.Client

trait TrustClient[F[_]] {
  def getPublicTrust: PeerResponse[F, PublicTrust]
}

object TrustClient {

  def make[F[_]: Async](client: Client[F], session: Session[F]): TrustClient[F] =
    new TrustClient[F] {

      def getPublicTrust: PeerResponse[F, PublicTrust] =
        PeerResponse("trust", GET)(client, session) { (req, c) =>
          c.expect(req)
        }
    }
}
