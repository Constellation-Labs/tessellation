package io.constellationnetwork.error

import cats.syntax.option._

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.encoder
import derevo.derive
import enumeratum.values._
import io.circe.Encoder

@derive(encoder, eqv, show)
case class ApplicationErrorDetails(reason: String)

sealed abstract class ApplicationError(
  val value: Int,
  val message: String,
  val retriable: Boolean,
  val description: Option[String] = None,
  val details: Option[ApplicationErrorDetails] = None
) extends IntEnumEntry

object ApplicationError extends IntEnum[ApplicationError] with ApplicationErrorEncoder {
  val values = findValues

  case class UnknownError(error: Throwable)
      extends ApplicationError(
        value = 1,
        message = "Unknown error",
        retriable = true,
        details = ApplicationErrorDetails(error.getMessage).some
      )
  case object UnprocessableEntity extends ApplicationError(value = 2, message = "Unprocessable entity", retriable = false)
  case class InvalidRequestBody(reason: String)
      extends ApplicationError(
        value = 3,
        message = "Invalid request body",
        retriable = false,
        details = ApplicationErrorDetails(reason).some
      )
  case class InternalError(reason: String, canRetry: Boolean)
      extends ApplicationError(value = 4, message = "Internal error", retriable = canRetry, description = reason.some)
}

trait ApplicationErrorEncoder {
  implicit def encoder: Encoder[ApplicationError] =
    Encoder[ApplicationErrorJson].contramap[ApplicationError](ApplicationErrorJson.apply).mapJson(_.deepDropNullValues)
}

@derive(encoder, eqv, show)
case class ApplicationErrorJson(
  code: Int,
  message: String,
  retriable: Boolean,
  description: Option[String],
  details: Option[ApplicationErrorDetails]
)

object ApplicationErrorJson {
  def apply(e: ApplicationError): ApplicationErrorJson = ApplicationErrorJson(e.value, e.message, e.retriable, e.description, e.details)
}
