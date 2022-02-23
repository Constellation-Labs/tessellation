package org.tessellation.http.routes

import cats.effect.Async
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import cats.syntax.option._

import org.tessellation.domain.snapshot.{TimeSnapshotTrigger, TipSnapshotTrigger}
import org.tessellation.infrastructure.snapshot.toEvent
import org.tessellation.modules.{Services, Storages}
import org.tessellation.schema.cluster.SessionAlreadyExists
import org.tessellation.schema.height.Height
import org.tessellation.schema.node.InvalidNodeStateTransition
import org.tessellation.sdk.infrastructure.consensus.message.ConsensusEvent

import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import org.http4s.dsl.Http4sDsl
import org.http4s.server.Router

final case class DebugRoutes[F[_]: Async](
  storages: Storages[F],
  services: Services[F]
) extends Http4sDsl[F] {

  private[routes] val prefixPath = "/debug"

  private val httpRoutes: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root           => Ok()
    case GET -> Root / "peers" => Ok(storages.cluster.getPeers)
    case POST -> Root / "create-session" =>
      services.session.createSession.flatMap(Ok(_)).recoverWith {
        case e: InvalidNodeStateTransition => Conflict(e.getMessage)
        case SessionAlreadyExists          => Conflict(s"Session already exists.")
      }
    case POST -> Root / "gossip" / "spread" / IntVar(intContent) =>
      services.gossip.spread(intContent.some) >> Ok()
    case POST -> Root / "gossip" / "spread" / strContent =>
      services.gossip.spreadCommon(strContent) >> Ok()
    case POST -> Root / "consensus" / "trigger" =>
      services.gossip.spread(ConsensusEvent(toEvent(TimeSnapshotTrigger()))) >> Ok()
    case POST -> Root / "consensus" / "trigger" / LongVar(height) =>
      services.gossip.spread(ConsensusEvent(toEvent(TipSnapshotTrigger(Height(height))))) >> Ok()
  }

  val routes: HttpRoutes[F] = Router(
    prefixPath -> httpRoutes
  )
}
