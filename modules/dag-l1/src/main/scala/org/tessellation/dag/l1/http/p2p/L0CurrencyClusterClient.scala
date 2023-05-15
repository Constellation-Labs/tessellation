package org.tessellation.dag.l1.http.p2p

import org.tessellation.schema.Block
import org.tessellation.sdk.http.p2p.PeerResponse
import org.tessellation.sdk.http.p2p.PeerResponse.PeerResponse
import org.tessellation.security.signature.Signed

import org.http4s.Method.POST
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import org.http4s.client.Client

trait L0CurrencyClusterClient[F[_]] {
  def sendL1Output(output: Signed[Block]): PeerResponse[F, Boolean]
}

object L0CurrencyClusterClient {

  def make[F[_]](pathPrefix: String, client: Client[F]): L0CurrencyClusterClient[F] =
    new L0CurrencyClusterClient[F] {

      def sendL1Output(output: Signed[Block]): PeerResponse[F, Boolean] =
        PeerResponse(s"$pathPrefix/l1-output", POST)(client) { (req, c) =>
          c.successful(req.withEntity(output))
        }
    }
}
