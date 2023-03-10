package org.tessellation.rosetta.domain.error

import cats.syntax.option._

import org.tessellation.ext.derevo.magnoliaCustomizable.snakeCaseConfiguration

import derevo.circe.magnolia.{customizableEncoder, encoder}
import derevo.derive
import enumeratum.values._
import io.circe.Encoder

@derive(customizableEncoder)
case class RosettaErrorDetails(reason: String)

sealed abstract class RosettaError(
  val value: Int,
  val message: String,
  val retriable: Boolean,
  val description: Option[String] = None,
  val details: Option[RosettaErrorDetails] = None
) extends IntEnumEntry

object RosettaError extends IntEnum[RosettaError] with RosettaErrorEncoder {
  val values = findValues

  case class UnknownError(error: Throwable)
      extends RosettaError(
        value = 1,
        message = "Unknown error",
        retriable = true,
        details = RosettaErrorDetails(error.getMessage).some
      )
  case object UnprocessableEntity extends RosettaError(value = 2, message = "Unprocessable entity", retriable = false)
  case class InvalidRequestBody(reason: String)
      extends RosettaError(value = 3, message = "Invalid request body", retriable = false, details = RosettaErrorDetails(reason).some)
  case object InvalidNetworkIdentifier extends RosettaError(value = 4, message = "Invalid network identifier", retriable = false)
  case object InvalidPublicKey extends RosettaError(value = 5, message = "Invalid public key", retriable = false)
  case object MalformedTransaction
      extends RosettaError(
        value = 6,
        message = "Malformed transaction",
        retriable = false,
        description = Some("Unable to decode the transaction.")
      )
  case object UnsupportedOperation
      extends RosettaError(
        value = 7,
        message = "Unsupported operation",
        retriable = false,
        description = Some("The operation is not supported.")
      )
}

trait RosettaErrorEncoder {

  implicit def rosettaErrorEncoder =
    Encoder[RosettaErrorJson].contramap[RosettaError](RosettaErrorJson.apply)
}

@derive(encoder)
case class RosettaErrorJson(
  code: Int,
  message: String,
  description: Option[String],
  retriable: Boolean,
  details: Option[RosettaErrorDetails]
)

object RosettaErrorJson {
  def apply(r: RosettaError): RosettaErrorJson = RosettaErrorJson(r.value, r.message, r.description, r.retriable, r.details)
}
