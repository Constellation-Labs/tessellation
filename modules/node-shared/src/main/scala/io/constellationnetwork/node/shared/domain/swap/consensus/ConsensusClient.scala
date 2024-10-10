package io.constellationnetwork.node.shared.domain.swap.consensus

import cats.effect.Sync
import cats.syntax.functor._

import io.constellationnetwork.currency.swap.ConsensusInput
import io.constellationnetwork.node.shared.http.p2p.PeerResponse
import io.constellationnetwork.node.shared.http.p2p.PeerResponse.PeerResponse
import io.constellationnetwork.security.signature.Signed

import io.circe.Encoder
import org.http4s.Method.POST
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import org.http4s.client.Client

trait ConsensusClient[F[_]] {
  def sendConsensusData[A <: ConsensusInput.PeerConsensusInput](data: Signed[A])(implicit e: Encoder[A]): PeerResponse[F, Unit]
}

object ConsensusClient {
  def make[F[_]: Sync](client: Client[F]): ConsensusClient[F] =
    new ConsensusClient[F] {

      def sendConsensusData[A <: ConsensusInput.PeerConsensusInput](data: Signed[A])(implicit e: Encoder[A]): PeerResponse[F, Unit] =
        PeerResponse("consensus/swap-transaction", POST)(client) { (req, c) =>
          c.successful(req.withEntity(data)).void
        }
    }
}
