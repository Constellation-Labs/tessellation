package org.tessellation.sdk.infrastructure.consensus

import cats.Order
import cats.effect.Async
import cats.syntax.all._

import org.tessellation.sdk.infrastructure.consensus.message.{GetConsensusOutcomeRequest, RegistrationResponse}

import eu.timepit.refined.auto._
import io.circe.{Decoder, Encoder}
import org.http4s._
import org.http4s.circe.CirceEntityCodec._
import org.http4s.dsl._
import org.http4s.server.Router

class ConsensusRoutes[F[_]: Async, Key: Order: Encoder: Decoder, Artifact: Encoder, Context: Encoder](
  storage: ConsensusStorage[F, _, Key, Artifact, Context]
) extends Http4sDsl[F] {

  private val prefixPath = "/consensus"

  private val p2p: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root / "registration" =>
      storage.getOwnRegistration.map(RegistrationResponse(_)).flatMap(Ok(_))
    case GET -> Root / "latest" / "outcome" =>
      storage.getLastConsensusOutcome.flatMap(Ok(_))
    case req @ POST -> Root / "specific" / "outcome" => // POST used instead of GET because `Key` can't be used be in path
      for {
        outcomeRequest <- req.as[GetConsensusOutcomeRequest[Key]]
        result <- storage.getLastConsensusOutcome.flatMap {
          case Some(value) if value.key === outcomeRequest.key => Ok(value.some)
          case Some(value) if value.key > outcomeRequest.key   => Conflict()
          case _                                               => Ok(none[GetConsensusOutcomeRequest[Key]])
        }
      } yield result
  }

  val p2pRoutes: HttpRoutes[F] = Router(
    prefixPath -> p2p
  )

}
