package io.constellationnetwork.currency.l0.http.routes

import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.ext.http4s.HashVar
import io.constellationnetwork.node.shared.domain.block.processing._
import io.constellationnetwork.node.shared.infrastructure.consensus.ValidationErrorStorage
import io.constellationnetwork.node.shared.snapshot.currency.CurrencySnapshotEvent
import io.constellationnetwork.routes.internal._

import derevo.circe.magnolia.encoder
import derevo.derive
import eu.timepit.refined.auto._
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityEncoder._
import org.http4s.dsl.Http4sDsl

final case class TransactionValidationErrorRoutes[F[_]: Async](
  currencySnapshotValidationErrorStorage: ValidationErrorStorage[F, CurrencySnapshotEvent, BlockRejectionReason]
) extends Http4sDsl[F]
    with PublicRoutes[F] {

  @derive(encoder)
  case class ValidationError(
    message: String,
    description: Option[String] = None,
    details: Option[BlockRejectionReason] = None
  )

  object ValidationError {
    def apply(a: BlockRejectionReason): ValidationError = {
      val message = a match {
        case e: DataBlockNotAccepted     => s"data block not accepted: ${e.reason}"
        case InvalidCurrencyMessageEvent => "invalid currency message"
        case e: ParentNotFound           => s"parent not found: ${e.parent}"
        case e: RejectedTransaction      => s"rejected transaction: ${e.reason} tx=${e.tx}"
        case SnapshotOrdinalUnavailable  => "snapshot ordinal unavailable"
        case e: ValidationFailed         => s"validation failed: ${e.reasons}"
      }
      ValidationError(
        message = message,
        details = Some(a)
      )
    }
  }

  protected val prefixPath: InternalUrlPrefix = "/transactions"

  protected val public: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root / HashVar(txHash) / "errors" =>
      currencySnapshotValidationErrorStorage
        .getTransaction(txHash)
        .map(_.map(ValidationError(_)))
        .flatMap(Ok(_))
  }
}
