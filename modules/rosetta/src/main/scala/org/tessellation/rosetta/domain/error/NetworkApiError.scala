package org.tessellation.rosetta.domain.error

import scala.util.control.NoStackTrace

import derevo.cats.{eqv, show}
import derevo.derive

@derive(eqv, show)
sealed trait NetworkApiError extends NoStackTrace

object NetworkApiError {
  implicit class NetworkApiErrorOps(ne: NetworkApiError) {
    def toRosettaError: RosettaError = ne match {
      case LatestSnapshotNotFound => RosettaError.InternalError(s"Latest snapshot not found", canRetry = true)
    }
  }
}

case object LatestSnapshotNotFound extends NetworkApiError
