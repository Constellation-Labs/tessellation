package org.tessellation.dag.l1.http.p2p

import org.tessellation.currency.DataUpdate
import org.tessellation.currency.dataApplication.DataApplicationBlock
import org.tessellation.schema.Block
import org.tessellation.sdk.http.p2p.PeerResponse
import org.tessellation.sdk.http.p2p.PeerResponse.PeerResponse
import org.tessellation.security.signature.Signed

import io.circe.Encoder
import org.http4s.Method.POST
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import org.http4s.client.Client

trait L0CurrencyClusterClient[F[_], B <: Block[_]] {
  def sendL1Output(output: Signed[B]): PeerResponse[F, Boolean]
  def sendDataApplicationBlock(block: Signed[DataApplicationBlock])(
    implicit encoder: Encoder[DataUpdate]
  ): PeerResponse[F, Boolean]
}

object L0CurrencyClusterClient {

  def make[F[_], B <: Block[_]: Encoder](pathPrefix: String, client: Client[F]): L0CurrencyClusterClient[F, B] =
    new L0CurrencyClusterClient[F, B] {

      def sendL1Output(output: Signed[B]): PeerResponse[F, Boolean] =
        PeerResponse(s"$pathPrefix/l1-output", POST)(client) { (req, c) =>
          c.successful(req.withEntity(output))
        }

      def sendDataApplicationBlock(block: Signed[DataApplicationBlock])(implicit encoder: Encoder[DataUpdate]): PeerResponse[F, Boolean] =
        PeerResponse(s"$pathPrefix/l1-data-output", POST)(client) { (req, c) =>
          c.successful(req.withEntity(block))
        }

    }
}
