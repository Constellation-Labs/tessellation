package io.constellationnetwork.node.shared.infrastructure.consensus

import cats.Order
import cats.effect.Async
import cats.effect.std.Queue
import cats.syntax.all._

import io.constellationnetwork.node.shared.infrastructure.consensus.message.{GetConsensusOutcomeRequest, RegistrationResponse}
import io.constellationnetwork.routes.internal._
import io.constellationnetwork.schema.gossip.RumorRaw
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.{Hashed, HasherSelector}

import eu.timepit.refined.auto._
import io.circe.{Decoder, Encoder}
import monocle.Lens
import org.http4s._
import org.http4s.circe.CirceEntityCodec._
import org.http4s.dsl._
import org.http4s.server.Router

class ConsensusRoutes[F[_]: Async: HasherSelector, Key: Order: Encoder: Decoder, Artifact, Context, ConStatus, Outcome: Encoder, Kind](
  storage: ConsensusStorage[F, _, Key, Artifact, Context, ConStatus, Outcome, Kind],
  rumorQueue: Queue[F, Hashed[RumorRaw]],
  historicalOutcome: Option[Key => F[Option[Outcome]]] = None
)(implicit _key: Lens[Outcome, Key])
    extends Http4sDsl[F] {

  private val prefixPath: InternalUrlPrefix = "/consensus"

  private val p2p: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root / "registration" =>
      storage.getOwnRegistrationKey.map(RegistrationResponse(_)).flatMap(Ok(_))
    case GET -> Root / "latest" / "outcome" =>
      storage.getLastConsensusOutcome.flatMap(Ok(_))
    case req @ POST -> Root / "specific" / "outcome" => // POST used instead of GET because `Key` can't be used be in path
      for {
        outcomeRequest <- req.as[GetConsensusOutcomeRequest[Key]]
        historical <- historicalOutcome.fold(none[Outcome].pure[F])(_(outcomeRequest.key))
        result <- historical.fold(storage.getLastConsensusOutcome.flatMap {
          case Some(value) if _key.get(value) === outcomeRequest.key => Ok(value.some)
          case Some(value) if _key.get(value) > outcomeRequest.key   => Conflict()
          case _                                                     => Ok(none[Outcome])
        })(value => Ok(value.some))
      } yield result
    case req @ POST -> Root / "push-rumor" =>
      HasherSelector[F].withCurrent { implicit hasher =>
        for {
          signedRumor <- req.as[Signed[RumorRaw]]
          hashedRumor <- signedRumor.toHashed[F]
          _ <- rumorQueue.offer(hashedRumor)
          result <- Ok()
        } yield result
      }
  }

  val p2pRoutes: HttpRoutes[F] = Router(
    prefixPath.value -> p2p
  )

}
