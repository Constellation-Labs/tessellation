package org.tessellation.rosetta.domain.error

import scala.util.control.NoStackTrace

import derevo.cats.eqv
import derevo.derive

@derive(eqv)
sealed trait ConstructionError extends NoStackTrace

object ConstructionError {
  implicit class ConstructionErrorOps(ce: ConstructionError) {
    def toRosettaError: RosettaError = ce match {
      case InvalidPublicKey     => RosettaError.InvalidPublicKey
      case MalformedTransaction => RosettaError.MalformedTransaction
    }
  }
}

case object InvalidPublicKey extends ConstructionError
case object MalformedTransaction extends ConstructionError
