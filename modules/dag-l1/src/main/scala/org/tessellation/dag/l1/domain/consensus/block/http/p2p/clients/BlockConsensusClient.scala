package org.tessellation.dag.l1.domain.consensus.block.http.p2p.clients

import cats.effect.Sync
import cats.syntax.functor._

import org.tessellation.dag.l1.domain.consensus.block.BlockConsensusInput.PeerBlockConsensusInput
import org.tessellation.schema.security.signature.Signed
import org.tessellation.sdk.http.p2p.PeerResponse
import org.tessellation.sdk.http.p2p.PeerResponse.PeerResponse

import org.http4s.Method.POST
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import org.http4s.client.Client

trait BlockConsensusClient[F[_]] {
  def sendConsensusData(data: Signed[PeerBlockConsensusInput]): PeerResponse[F, Unit]
}

object BlockConsensusClient {

  def make[F[_]: Sync](client: Client[F]): BlockConsensusClient[F] =
    new BlockConsensusClient[F] {

      def sendConsensusData(data: Signed[PeerBlockConsensusInput]): PeerResponse[F, Unit] =
        PeerResponse("consensus/data", POST)(client) { (req, c) =>
          c.successful(req.withEntity(data)).void
        }
    }
}
