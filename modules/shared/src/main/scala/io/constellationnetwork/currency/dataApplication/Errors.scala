package io.constellationnetwork.currency.dataApplication

import scala.util.control.NoStackTrace

trait DataApplicationValidationError {
  val message: String
}

object Errors {
  case object Noop extends DataApplicationValidationError {
    val message = "invalid update"
  }

  case class MissingDataUpdateTransaction() extends DataApplicationValidationError {
    val message = "Could not find any data update transaction"
  }

  case class DataApplicationFeeError(message: String) extends DataApplicationValidationError

  case object NotEnoughFee extends DataApplicationValidationError {
    val message = "Not enough fees"
  }

  case object SourceWalletNotEnoughBalance extends DataApplicationValidationError {
    val message = "Source wallet not enough balance"
  }

  case object MissingDataUpdateOfFeeTransaction extends DataApplicationValidationError {
    val message = "Could not find data update for provided fee transaction"
  }

  case object MissingFeeTransaction extends DataApplicationValidationError {
    val message = "Missing fee transaction"
  }

  case object SourceWalletNotSignTheTransaction extends DataApplicationValidationError {
    val message = "Source wallet should sign the transaction"
  }

  case object InvalidSignature extends DataApplicationValidationError {
    val message = "Invalid signature in data transactions"
  }

  case object NoValidDataTransactions extends DataApplicationValidationError {
    val message = "No valid data transactions found"
  }
}

case object UnexpectedInput extends NoStackTrace
