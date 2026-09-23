package io.constellationnetwork.currency.validations

import cats.data.{NonEmptyList, OptionT, ValidatedNec}
import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.DataTransaction.DataTransactions
import io.constellationnetwork.currency.dataApplication.DataUpdate.getDataUpdates
import io.constellationnetwork.currency.dataApplication.Errors.MissingDataUpdateTransaction
import io.constellationnetwork.currency.dataApplication.FeeTransaction.getByDataUpdate
import io.constellationnetwork.currency.dataApplication._
import io.constellationnetwork.currency.validations.FeeTransactionSignatureValidator.isEnabled
import io.constellationnetwork.currency.validations.FeeTransactionValidator.{
  validateAllFeeTransactionsWithSignerPolicy,
  validateFeeTransaction
}
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Balance
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.signature.Signed

object DataTransactionsValidator {
  private def validateDataTransactions[F[_]: Async: JsonSerializer: SecurityProvider](
    dataTransactions: DataTransactions,
    balances: Map[Address, Balance],
    dataApplication: BaseDataApplicationService[F],
    validateFee: SnapshotOrdinal => (
      Signed[DataUpdate],
      Option[Signed[FeeTransaction]]
    ) => F[ValidatedNec[DataApplicationValidationError, Unit]],
    feeValidationOrdinal: SnapshotOrdinal,
    globalSnapshotOrdinal: SnapshotOrdinal,
    feeTransactionSecurityActivationOrdinal: SnapshotOrdinal,
    validateEveryFeeTransaction: Boolean
  ): F[ValidatedNec[DataApplicationValidationError, Unit]] = {

    val dataUpdates = dataTransactions.collect {
      case Signed(dataUpdate: DataUpdate, proofs) => Signed(dataUpdate, proofs)
    }
    NonEmptyList.fromList(dataUpdates) match {
      case Some(value) if validateEveryFeeTransaction =>
        for {
          // Only the metagraph's own fee policy stays per-data-update. The signature, hash and balance
          // checks live in validateAllFeeTransactions, which sees every fee transaction in the envelope
          // rather than the one getByDataUpdate happens to return.
          perDataUpdateValidation <- value.traverse { dataUpdate =>
            getByDataUpdate(dataTransactions, dataUpdate.value, dataApplication.serializeUpdate)
              .flatMap(maybeFeeTransaction => validateFee(feeValidationOrdinal)(dataUpdate, maybeFeeTransaction))
          }.map(_.reduce)
          allFeeTransactionsValidation <- validateAllFeeTransactionsWithSignerPolicy(
            dataTransactions,
            balances,
            dataApplication,
            allowSourceAuthorizedCoSigners = isEnabled(globalSnapshotOrdinal, feeTransactionSecurityActivationOrdinal)
          )
        } yield perDataUpdateValidation.productR(allFeeTransactionsValidation)

      case Some(value) =>
        value.traverse { dataUpdate =>
          for {
            maybeFeeTransaction <- getByDataUpdate(dataTransactions, dataUpdate.value, dataApplication.serializeUpdate)
            feeTransactionValidation <- validateFeeTransaction(
              maybeFeeTransaction,
              dataTransactions,
              balances,
              dataApplication,
              globalSnapshotOrdinal,
              feeTransactionSecurityActivationOrdinal
            )
            feeAgainstDataUpdateValidation <- validateFee(feeValidationOrdinal)(dataUpdate, maybeFeeTransaction)
          } yield
            feeTransactionValidation
              .productR(feeAgainstDataUpdateValidation)
        }
          .map(_.reduce)

      case None =>
        MissingDataUpdateTransaction
          .asInstanceOf[DataApplicationValidationError]
          .invalidNec[Unit]
          .pure[F]
    }

  }

  def validateDataTransactionsL1[F[_]: Async: JsonSerializer: L1NodeContext: SecurityProvider](
    dataTransactions: DataTransactions,
    dataApplication: BaseDataApplicationL1Service[F],
    balances: Map[Address, Balance],
    gsOrdinal: SnapshotOrdinal,
    feeTransactionSecurityActivationOrdinal: SnapshotOrdinal
  ): F[ValidatedNec[DataApplicationValidationError, Unit]] =
    for {
      dataUpdates <- OptionT
        .fromOption(NonEmptyList.fromList(getDataUpdates(List(dataTransactions))))
        .getOrRaise(new RuntimeException("Could not get data updates"))
      dataUpdatesValidation <- dataUpdates.traverse(dataApplication.validateUpdate(_)).map(_.reduce)
      dataTransactionsValidation <- validateDataTransactions(
        dataTransactions,
        balances,
        dataApplication,
        dataApplication.validateFee,
        gsOrdinal,
        gsOrdinal,
        feeTransactionSecurityActivationOrdinal,
        // L1 is an admission check and never replays signed history, so it always runs the current rules.
        validateEveryFeeTransaction = true
      )
    } yield dataUpdatesValidation.productR(dataTransactionsValidation)

  def validateDataTransactionsL0[F[_]: Async: JsonSerializer: L0NodeContext: SecurityProvider](
    dataTransactions: DataTransactions,
    dataApplication: BaseDataApplicationL0Service[F],
    balances: Map[Address, Balance],
    currencySnapshotOrdinal: SnapshotOrdinal,
    parentGlobalSnapshotOrdinal: SnapshotOrdinal,
    currentState: DataState[DataOnChainState, DataCalculatedState],
    feeTransactionSecurityActivationOrdinal: SnapshotOrdinal,
    validateEveryFeeTransaction: Boolean
  ): F[ValidatedNec[DataApplicationValidationError, Unit]] =
    for {
      dataUpdates <- OptionT
        .fromOption(NonEmptyList.fromList(getDataUpdates(List(dataTransactions))))
        .getOrRaise(new RuntimeException("Could not get data updates"))
      dataUpdatesValidation <- dataApplication.validateData(currentState, dataUpdates)
      dataTransactionsValidation <- validateDataTransactions(
        dataTransactions,
        balances,
        dataApplication,
        dataApplication.validateFee,
        currencySnapshotOrdinal,
        parentGlobalSnapshotOrdinal,
        feeTransactionSecurityActivationOrdinal,
        validateEveryFeeTransaction
      )
    } yield dataUpdatesValidation.productR(dataTransactionsValidation)

}
