package org.tessellation.node.shared.http.routes

import cats.effect.Async
import cats.syntax.applicativeError._
import cats.syntax.flatMap._

import org.tessellation.node.shared.domain.cluster.services.Cluster
import org.tessellation.node.shared.ext.http4s.refined.RefinedRequestDecoder
import org.tessellation.routes.internal._
import org.tessellation.schema.cluster.SessionDoesNotExist
import org.tessellation.schema.peer.SignRequest
import org.tessellation.security.HasherSelector

import eu.timepit.refined.auto._
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import org.http4s.dsl.Http4sDsl
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

final case class RegistrationRoutes[F[_]: Async: HasherSelector](cluster: Cluster[F]) extends Http4sDsl[F] with P2PPublicRoutes[F] {

  implicit val logger: SelfAwareStructuredLogger[F] = Slf4jLogger.getLogger[F]

  protected[routes] val prefixPath: InternalUrlPrefix = "/registration"

  protected val p2pPublic: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root / "request" =>
      HasherSelector[F].withCurrent { implicit hasher =>
        cluster.getRegistrationRequest
      }
        .flatMap(Ok(_))
        .recoverWith {
          case SessionDoesNotExist =>
            Conflict("Peer does not have an active session.")
        }

    case req @ POST -> Root / "sign" =>
      req.decodeR[SignRequest] { signRequest =>
        HasherSelector[F].withCurrent { implicit hasher =>
          cluster
            .signRequest(signRequest)
        }
          .flatMap(Ok(_))
          .handleErrorWith(e => logger.error(e)(s"An error occured!") >> BadRequest())
      }
  }
}
