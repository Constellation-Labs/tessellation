package org.tessellation.currency.l1.http.p2p

import org.tessellation.currency.dataApplication.dataApplication.DataApplicationBlock
import org.tessellation.dag.l1.http.p2p.L0BlockOutputClient
import org.tessellation.schema.Block
import org.tessellation.sdk.http.p2p.PeerResponse
import org.tessellation.sdk.http.p2p.PeerResponse.PeerResponse
import org.tessellation.security.signature.Signed

import io.circe.Encoder
import org.http4s.Method.POST
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import org.http4s.client.Client

trait L0CurrencyBlockOutputClient[F[_]] {
  def sendL1Output(output: Signed[Block]): PeerResponse[F, Boolean]
  def sendL1DataOutput(output: Signed[DataApplicationBlock]): PeerResponse[F, Boolean]
}

object L0CurrencyBlockOutputClient {
  def make[F[_]](l0BlockOutputClient: L0BlockOutputClient[F], client: Client[F])(
    implicit encoder: Encoder[DataApplicationBlock]
  ): L0CurrencyBlockOutputClient[F] =
    new L0CurrencyBlockOutputClient[F] {
      def sendL1Output(output: Signed[Block]): PeerResponse[F, Boolean] =
        l0BlockOutputClient.sendL1Output(output)

      def sendL1DataOutput(output: Signed[DataApplicationBlock]): PeerResponse[F, Boolean] =
        PeerResponse(s"currency/l1-data-output", POST)(client) { (req, c) =>
          c.successful(req.withEntity(output))
        }
    }
}
