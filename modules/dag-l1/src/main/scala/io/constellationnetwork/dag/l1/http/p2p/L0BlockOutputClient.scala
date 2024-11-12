package io.constellationnetwork.dag.l1.http.p2p

import io.constellationnetwork.currency.dataApplication.DataUpdate
import io.constellationnetwork.currency.dataApplication.dataApplication.DataApplicationBlock
import io.constellationnetwork.node.shared.http.p2p.PeerResponse
import io.constellationnetwork.node.shared.http.p2p.PeerResponse.PeerResponse
import io.constellationnetwork.schema.Block
import io.constellationnetwork.schema.swap.AllowSpendBlock
import io.constellationnetwork.security.signature.Signed

import io.circe.Encoder
import org.http4s.Method.POST
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import org.http4s.client.Client

trait L0BlockOutputClient[F[_]] {
  def sendL1Output(output: Signed[Block]): PeerResponse[F, Boolean]
  def sendDataApplicationBlock(block: Signed[DataApplicationBlock])(
    implicit encoder: Encoder[DataUpdate]
  ): PeerResponse[F, Boolean]
  def sendAllowSpendBlock(block: Signed[AllowSpendBlock]): PeerResponse[F, Boolean]
}

object L0BlockOutputClient {

  def make[F[_]](pathPrefix: String, client: Client[F]): L0BlockOutputClient[F] =
    new L0BlockOutputClient[F] {

      def sendL1Output(output: Signed[Block]): PeerResponse[F, Boolean] =
        PeerResponse(s"$pathPrefix/l1-output", POST)(client) { (req, c) =>
          c.successful(req.withEntity(output))
        }

      def sendDataApplicationBlock(block: Signed[DataApplicationBlock])(implicit encoder: Encoder[DataUpdate]): PeerResponse[F, Boolean] =
        PeerResponse(s"$pathPrefix/l1-data-output", POST)(client) { (req, c) =>
          c.successful(req.withEntity(block))
        }

      def sendAllowSpendBlock(block: Signed[AllowSpendBlock]): PeerResponse[F, Boolean] =
        PeerResponse(s"$pathPrefix/l1-allow-spend-output", POST)(client) { (req, c) =>
          c.successful(req.withEntity(block))
        }

    }
}
