package io.constellationnetwork.currency.validations

import cats.data.{NonEmptyList, OptionT, ValidatedNec}
import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.DataTransaction.DataTransactions
import io.constellationnetwork.currency.dataApplication.DataUpdate.getDataUpdates
import io.constellationnetwork.currency.dataApplication.Errors.MissingDataUpdateTransaction
import io.constellationnetwork.currency.dataApplication.FeeTransaction.getByDataUpdate
import io.constellationnetwork.currency.dataApplication._
import io.constellationnetwork.currency.validations.FeeTransactionValidator.{validateAllFeeTransactions, validateFeeTransaction}
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Balance
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.signature.Signed

object DataTransactionsValidator {
  private def validateDataTransactions[F[_]: Async: SecurityProvider](
    dataTransactions: DataTransactions,
    balances: Map[Address, Balance],
    dataApplication: BaseDataApplicationService[F],
    validateFee: SnapshotOrdinal => (
      Signed[DataUpdate],
      Option[Signed[FeeTransaction]]
    ) => F[ValidatedNec[DataApplicationValidationError, Unit]],
    gsOrdinal: SnapshotOrdinal,
    validateEveryFeeTransaction: Boolean
  ): F[ValidatedNec[DataApplicationValidationError, Unit]] = {

    val dataUpdates = dataTransactions.collect {
      case Signed(dataUpdate: DataUpdate, proofs) => Signed(dataUpdate, proofs)
    }
    NonEmptyList.fromList(dataUpdates) match {
      case Some(value) if validateEveryFeeTransaction =>
        for {
          // Only the metagraph's own fee policy is per-data-update on this path. The signature, hash and
          // balance checks live in validateAllFeeTransactions, which sees every fee transaction in the
          // envelope rather than the one getByDataUpdate happens to return.
          perDataUpdateValidation <- value.traverse { dataUpdate =>
            getByDataUpdate(dataTransactions, dataUpdate.value, dataApplication.serializeUpdate)
              .flatMap(maybeFeeTransaction => validateFee(gsOrdinal)(dataUpdate, maybeFeeTransaction))
          }.map(_.reduce)
          allFeeTransactionsValidation <- validateAllFeeTransactions(dataTransactions, balances, dataApplication)
        } yield perDataUpdateValidation.productR(allFeeTransactionsValidation)

      case Some(value) =>
        value.traverse { dataUpdate =>
          for {
            maybeFeeTransaction <- getByDataUpdate(dataTransactions, dataUpdate.value, dataApplication.serializeUpdate)
            feeTransactionValidation <- validateFeeTransaction(maybeFeeTransaction, dataTransactions, balances, dataApplication)
            feeAgainstDataUpdateValidation <- validateFee(gsOrdinal)(dataUpdate, maybeFeeTransaction)
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

  // L1 is an admission check and never replays signed history, so it always runs the current rules.
  def validateDataTransactionsL1[F[_]: Async: L1NodeContext: SecurityProvider](
    dataTransactions: DataTransactions,
    dataApplication: BaseDataApplicationL1Service[F],
    balances: Map[Address, Balance],
    gsOrdinal: SnapshotOrdinal
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
        validateEveryFeeTransaction = true
      )
    } yield dataUpdatesValidation.productR(dataTransactionsValidation)

  // L0 runs inside snapshot acceptance, which is re-executed verbatim when a signed snapshot is replayed,
  // so the caller decides which rules apply from an ordinal recorded in the history being replayed.
  def validateDataTransactionsL0[F[_]: Async: L0NodeContext: SecurityProvider](
    dataTransactions: DataTransactions,
    dataApplication: BaseDataApplicationL0Service[F],
    balances: Map[Address, Balance],
    gsOrdinal: SnapshotOrdinal,
    currentState: DataState[DataOnChainState, DataCalculatedState],
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
        gsOrdinal,
        validateEveryFeeTransaction
      )
    } yield dataUpdatesValidation.productR(dataTransactionsValidation)

}
