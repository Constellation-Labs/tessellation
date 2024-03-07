package org.tessellation.node.shared.infrastructure.consensus

import cats.effect.Async

import org.tessellation.node.shared.domain.cluster.services.Session
import org.tessellation.node.shared.http.p2p.PeerResponse
import org.tessellation.node.shared.http.p2p.PeerResponse.PeerResponse
import org.tessellation.node.shared.infrastructure.consensus.message.{GetConsensusOutcomeRequest, RegistrationResponse}

import io.circe.{Decoder, Encoder}
import org.http4s.Method.{GET, POST}
import org.http4s.circe.CirceEntityCodec._
import org.http4s.client.Client

trait ConsensusClient[F[_], Key, Outcome] {

  def getRegistration: PeerResponse[F, RegistrationResponse[Key]]

  def getLatestConsensusOutcome: PeerResponse[F, Option[Outcome]]

  def getSpecificConsensusOutcome(
    request: GetConsensusOutcomeRequest[Key]
  ): PeerResponse[F, Option[Outcome]]

}

object ConsensusClient {
  def make[F[_]: Async, Key: Encoder: Decoder, Outcome: Decoder](
    client: Client[F],
    session: Session[F]
  ): ConsensusClient[F, Key, Outcome] =
    new ConsensusClient[F, Key, Outcome] {

      def getRegistration: PeerResponse[F, RegistrationResponse[Key]] = PeerResponse("consensus/registration", GET)(client, session) {
        (req, c) =>
          c.expect[RegistrationResponse[Key]](req)
      }

      def getLatestConsensusOutcome: PeerResponse[F, Option[Outcome]] =
        PeerResponse("consensus/latest/outcome", GET)(client, session) { (req, c) =>
          c.expect(req)
        }

      def getSpecificConsensusOutcome(
        request: GetConsensusOutcomeRequest[Key]
      ): PeerResponse[F, Option[Outcome]] =
        PeerResponse("consensus/specific/outcome", POST)(client, session) { (req, c) =>
          c.expect(req.withEntity(request))
        }
    }
}
