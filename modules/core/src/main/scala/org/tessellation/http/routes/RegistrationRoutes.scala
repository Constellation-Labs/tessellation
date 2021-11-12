package org.tessellation.http.routes

import cats.effect.Async
import cats.syntax.applicativeError._
import cats.syntax.flatMap._

import org.tessellation.domain.cluster.services.Cluster
import org.tessellation.ext.http4s.refined._
import org.tessellation.schema.cluster.SessionDoesNotExist
import org.tessellation.schema.peer.SignRequest

import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import org.http4s.dsl.Http4sDsl
import org.http4s.server.Router
import org.typelevel.log4cats.slf4j.Slf4jLogger

final case class RegistrationRoutes[F[_]: Async](cluster: Cluster[F]) extends Http4sDsl[F] {

  implicit val logger = Slf4jLogger.getLogger[F]

  private[routes] val prefixPath = "/registration"

  private val httpRoutes: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root / "request" =>
      cluster.getRegistrationRequest
        .flatMap(Ok(_))
        .recoverWith {
          case SessionDoesNotExist =>
            Conflict("Peer does not have an active session.")
        }

    case req @ POST -> Root / "sign" =>
      req.decodeR[SignRequest] { signRequest =>
        cluster
          .signRequest(signRequest)
          .flatMap(Ok(_))
          .handleErrorWith(e => logger.error(e)(s"An error occured!") >> BadRequest())
      }
  }

  val p2pRoutes: HttpRoutes[F] = Router(
    prefixPath -> httpRoutes
  )
}
