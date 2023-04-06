package org.tessellation.rosetta.domain.error

import scala.util.control.NoStackTrace

import derevo.cats.{eqv, show}
import derevo.derive

@derive(eqv, show)
sealed trait ConstructionError extends NoStackTrace

object ConstructionError {
  implicit class ConstructionErrorOps(ce: ConstructionError) {
    def toRosettaError: RosettaError = ce match {
      case InvalidNumberOfOperations(required) => RosettaError.InvalidRequestBody(s"Exactly $required operations are required.")
      case InvalidOperationAmount(amount) =>
        RosettaError.InternalError(s"Unable to construct transaction amount from $amount.", canRetry = true)
      case InvalidPublicKey         => RosettaError.InvalidPublicKey
      case InvalidSuggestedFee      => RosettaError.InvalidRequestBody("Suggested Fee must be positive")
      case MalformedTransaction     => RosettaError.MalformedTransaction
      case MissingAccountIdentifier => RosettaError.InvalidRequestBody("Missing AccountIdentifier")
      case NegationPairMismatch =>
        RosettaError.InvalidRequestBody("The amount of the first operation must be the negation of the second operation.")
      case SerializationError(reason) => RosettaError.InternalError(s"Serialization failed: $reason", canRetry = true)
      case UnsupportedOperation       => RosettaError.UnsupportedOperation
    }
  }
}

case class InvalidNumberOfOperations(numberOfOperationsRequired: Int) extends ConstructionError
case class InvalidOperationAmount(amount: Long) extends ConstructionError
case object InvalidPublicKey extends ConstructionError
case object InvalidSuggestedFee extends ConstructionError
case object MalformedTransaction extends ConstructionError
case object MissingAccountIdentifier extends ConstructionError
case object NegationPairMismatch extends ConstructionError
case object UnsupportedOperation extends ConstructionError
case class SerializationError(reason: String) extends ConstructionError
