package io.constellationnetwork.currency.l1.http.p2p

import io.constellationnetwork.currency.dataApplication.dataApplication.DataApplicationBlock
import io.constellationnetwork.dag.l1.http.p2p.L0BlockOutputClient
import io.constellationnetwork.node.shared.http.p2p.PeerResponse
import io.constellationnetwork.node.shared.http.p2p.PeerResponse.PeerResponse
import io.constellationnetwork.schema.Block
import io.constellationnetwork.schema.swap.AllowSpendBlock
import io.constellationnetwork.schema.tokenLock.TokenLockBlock
import io.constellationnetwork.security.signature.Signed

import io.circe.Encoder
import org.http4s.Method.POST
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import org.http4s.client.Client

trait L0CurrencyBlockOutputClient[F[_]] {
  def sendL1Output(output: Signed[Block]): PeerResponse[F, Boolean]
  def sendL1DataOutput(output: Signed[DataApplicationBlock]): PeerResponse[F, Boolean]
  def sendL1AllowSpendOutput(output: Signed[AllowSpendBlock]): PeerResponse[F, Boolean]
  def sendL1TokenLockOutput(output: Signed[TokenLockBlock]): PeerResponse[F, Boolean]
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
      def sendL1AllowSpendOutput(output: Signed[AllowSpendBlock]): PeerResponse[F, Boolean] =
        PeerResponse(s"currency/l1-allow-spend-output", POST)(client) { (req, c) =>
          c.successful(req.withEntity(output))
        }
      def sendL1TokenLockOutput(output: Signed[TokenLockBlock]): PeerResponse[F, Boolean] =
        PeerResponse(s"currency/l1-token-lock-output", POST)(client) { (req, c) =>
          c.successful(req.withEntity(output))
        }
    }
}
