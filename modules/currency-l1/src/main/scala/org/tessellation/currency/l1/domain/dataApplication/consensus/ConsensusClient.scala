package org.tessellation.currency.l1.domain.dataApplication.consensus

import cats.effect.Sync
import cats.syntax.functor._

import org.tessellation.currency.dataApplication.ConsensusInput
import org.tessellation.node.shared.http.p2p.PeerResponse
import org.tessellation.node.shared.http.p2p.PeerResponse.PeerResponse
import org.tessellation.security.signature.Signed

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
        PeerResponse("consensus/data-application", POST)(client) { (req, c) =>
          c.successful(req.withEntity(data)).void
        }
    }
}
