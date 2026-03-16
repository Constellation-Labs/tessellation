package io.constellationnetwork.node.shared.infrastructure.consensus

import cats.effect.Async

import io.constellationnetwork.node.shared.domain.cluster.services.Session
import io.constellationnetwork.node.shared.http.p2p.PeerResponse
import io.constellationnetwork.node.shared.http.p2p.PeerResponse.PeerResponse
import io.constellationnetwork.node.shared.infrastructure.consensus.message.{GetConsensusOutcomeRequest, RegistrationResponse}
import io.constellationnetwork.schema.gossip.RumorRaw
import io.constellationnetwork.security.signature.Signed

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

  /** Push a signed rumor directly to a peer for low-latency consensus declaration delivery. */
  def pushRumor(rumor: Signed[RumorRaw]): PeerResponse[F, Boolean]

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

      def pushRumor(rumor: Signed[RumorRaw]): PeerResponse[F, Boolean] =
        PeerResponse("consensus/push-rumor", POST)(client, session) { (req, c) =>
          c.successful(req.withEntity(rumor))
        }
    }
}
