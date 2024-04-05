package org.tessellation.node.shared.http.routes

import cats.data.Validated.{Invalid, Valid}
import cats.effect.Async
import cats.syntax.all._

import org.tessellation.kernel._
import org.tessellation.node.shared.infrastructure.snapshot.services.CurrencyMessageService
import org.tessellation.node.shared.snapshot.currency.{CurrencyMessageEvent, CurrencySnapshotEvent}
import org.tessellation.routes.internal._
import org.tessellation.schema.currencyMessage.CurrencyMessage
import org.tessellation.schema.http.{ErrorCause, ErrorResponse}
import org.tessellation.security.signature.Signed

import eu.timepit.refined.auto._
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec.{circeEntityDecoder, circeEntityEncoder}
import org.http4s.dsl.Http4sDsl
import org.typelevel.log4cats.slf4j.Slf4jLogger

class CurrencyMessageRoutes[F[_]: Async](
  mkCell: CurrencySnapshotEvent => Cell[F, StackF, _, Either[CellError, Ω], _],
  service: CurrencyMessageService[F]
) extends Http4sDsl[F]
    with PublicRoutes[F] {

  private val logger = Slf4jLogger.getLoggerFromName("CurrencyMessageRoutes")

  protected val prefixPath: InternalUrlPrefix = "/currency"

  protected val public: HttpRoutes[F] = HttpRoutes.of[F] {
    case req @ POST -> Root / "message" =>
      req
        .as[Signed[CurrencyMessage]]
        .flatMap(service.validate)
        .flatMap {
          case Invalid(errors) =>
            logger
              .warn(s"Message is invalid, reason: ${errors.show}")
              .as(ErrorResponse(errors.map(e => ErrorCause(e.show)).toNonEmptyList))
              .flatMap(BadRequest(_))
          case Valid(message) =>
            mkCell(CurrencyMessageEvent(message)).run() >> NoContent()
        }
  }

}
