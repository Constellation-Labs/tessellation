package org.tessellation.sdk.infrastructure.consensus

import cats.effect.Async

import org.tessellation.kryo.KryoSerializer
import org.tessellation.sdk.domain.cluster.services.Session
import org.tessellation.sdk.http.p2p.PeerResponse
import org.tessellation.sdk.http.p2p.PeerResponse.PeerResponse
import org.tessellation.sdk.infrastructure.consensus.message.{GetConsensusOutcomeRequest, RegistrationResponse}

import io.circe.{Decoder, Encoder}
import org.http4s.Method.{GET, POST}
import org.http4s.circe.CirceEntityCodec._
import org.http4s.client.Client

trait ConsensusClient[F[_], Key, Artifact] {

  def getRegistration: PeerResponse[F, RegistrationResponse[Key]]

  def getLatestConsensusOutcome: PeerResponse[F, Option[ConsensusOutcome[Key, Artifact]]]

  def getSpecificConsensusOutcome(request: GetConsensusOutcomeRequest[Key]): PeerResponse[F, Option[ConsensusOutcome[Key, Artifact]]]

}

object ConsensusClient {
  def make[F[_]: Async: KryoSerializer, Key: Encoder: Decoder, Artifact: Decoder](
    client: Client[F],
    session: Session[F]
  ): ConsensusClient[F, Key, Artifact] =
    new ConsensusClient[F, Key, Artifact] {

      def getRegistration: PeerResponse[F, RegistrationResponse[Key]] = PeerResponse("consensus/registration", GET)(client, session) {
        (req, c) =>
          c.expect[RegistrationResponse[Key]](req)
      }

      def getLatestConsensusOutcome: PeerResponse[F, Option[ConsensusOutcome[Key, Artifact]]] =
        PeerResponse("consensus/latest/outcome", GET)(client, session) { (req, c) =>
          c.expect(req)
        }

      def getSpecificConsensusOutcome(request: GetConsensusOutcomeRequest[Key]): PeerResponse[F, Option[ConsensusOutcome[Key, Artifact]]] =
        PeerResponse("consensus/specific/outcome", POST)(client, session) { (req, c) =>
          c.expect(req.withEntity(request))
        }
    }
}
