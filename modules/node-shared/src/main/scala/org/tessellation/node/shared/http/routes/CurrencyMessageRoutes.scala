package org.tessellation.node.shared.http.routes

import cats.Show
import cats.data.Validated.{Invalid, Valid}
import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.show._

import org.tessellation.node.shared.infrastructure.snapshot.services.CurrencyMessageService
import org.tessellation.node.shared.infrastructure.snapshot.services.CurrencyMessageService.CurrencyMessageServiceErrorOr
import org.tessellation.routes.internal._
import org.tessellation.schema.currencyMessage.CurrencyMessage
import org.tessellation.schema.http.{ErrorCause, ErrorResponse}
import org.tessellation.security.signature.Signed
import org.tessellation.security.{Hasher, SecurityProvider}

import eu.timepit.refined.auto._
import org.http4s.circe.CirceEntityCodec.{circeEntityDecoder, circeEntityEncoder}
import org.http4s.dsl.Http4sDsl
import org.http4s.{HttpRoutes, Response}
import org.typelevel.log4cats.slf4j.Slf4jLogger

class CurrencyMessageRoutes[F[_]: Async: SecurityProvider: Hasher](
  service: CurrencyMessageService[F]
) extends Http4sDsl[F]
    with PublicRoutes[F] {

  private val logger = Slf4jLogger.getLoggerFromName("CurrencyMessageRoutes")

  protected val prefixPath: InternalUrlPrefix = "/currency"

  protected val public: HttpRoutes[F] = HttpRoutes.of[F] {
    case req @ POST -> Root / "message" =>
      for {
        message <- req.as[Signed[CurrencyMessage]]
        response <- service.offer(message).flatMap(toResponse(message, _))
      } yield response

  }

  private def toResponse[A: Show](a: A, errorOr: CurrencyMessageServiceErrorOr[Unit]): F[Response[F]] =
    errorOr match {
      case Valid(_) => NoContent()
      case Invalid(errors) =>
        logger
          .warn(s"Message is invalid: ${a.show}, reason: ${errors.show}")
          .as(ErrorResponse(errors.map(e => ErrorCause(e.show)).toNonEmptyList))
          .flatMap(BadRequest(_))
    }

}
