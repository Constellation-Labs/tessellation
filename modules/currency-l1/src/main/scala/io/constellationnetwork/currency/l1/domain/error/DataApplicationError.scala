package io.constellationnetwork.currency.l1.domain.error

import scala.util.control.NoStackTrace

import io.constellationnetwork.error.ApplicationError

import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

@derive(encoder, decoder)
sealed trait DataApplicationError extends NoStackTrace

object DataApplicationError {
  implicit class DataApplicationErrorOps(e: DataApplicationError) {
    def toApplicationError: ApplicationError = e match {
      case InvalidDataUpdate(reason)     => ApplicationError.InvalidRequestBody(s"Invalid data update, reason: $reason")
      case InvalidSignature              => ApplicationError.InvalidRequestBody("Invalid signature")
      case GL0SnapshotOrdinalUnavailable => ApplicationError.InternalError("Last Global Snapshot ordinal not available", canRetry = true)
      case CurrencySnapshotUnavailable   => ApplicationError.InternalError("Last Currency Snapshot not available", canRetry = true)
      case EmptyValidDataTransactions    => ApplicationError.InternalError("Could not find any valid data transaction", canRetry = true)
    }
  }
}

case class InvalidDataUpdate(reason: String) extends DataApplicationError
case object InvalidSignature extends DataApplicationError
case object GL0SnapshotOrdinalUnavailable extends DataApplicationError
case object CurrencySnapshotUnavailable extends DataApplicationError
case object EmptyValidDataTransactions extends DataApplicationError
