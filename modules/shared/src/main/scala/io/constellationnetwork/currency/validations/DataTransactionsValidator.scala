package io.constellationnetwork.currency.validations

import cats.data.{NonEmptyList, OptionT, ValidatedNec}
import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.DataTransaction.DataTransactions
import io.constellationnetwork.currency.dataApplication.DataUpdate.getDataUpdates
import io.constellationnetwork.currency.dataApplication.Errors.MissingDataUpdateTransaction
import io.constellationnetwork.currency.dataApplication.FeeTransaction.getByDataUpdate
import io.constellationnetwork.currency.dataApplication._
import io.constellationnetwork.currency.validations.FeeTransactionValidator.validateFeeTransaction
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
    feeTransactionSecurityActivationOrdinal: SnapshotOrdinal
  ): F[ValidatedNec[DataApplicationValidationError, Unit]] = {

    val dataUpdates = dataTransactions.collect {
      case Signed(dataUpdate: DataUpdate, proofs) => Signed(dataUpdate, proofs)
    }
    NonEmptyList.fromList(dataUpdates) match {
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
        feeTransactionSecurityActivationOrdinal
      )
    } yield dataUpdatesValidation.productR(dataTransactionsValidation)

  def validateDataTransactionsL0[F[_]: Async: JsonSerializer: L0NodeContext: SecurityProvider](
    dataTransactions: DataTransactions,
    dataApplication: BaseDataApplicationL0Service[F],
    balances: Map[Address, Balance],
    currencySnapshotOrdinal: SnapshotOrdinal,
    parentGlobalSnapshotOrdinal: SnapshotOrdinal,
    currentState: DataState[DataOnChainState, DataCalculatedState],
    feeTransactionSecurityActivationOrdinal: SnapshotOrdinal
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
        feeTransactionSecurityActivationOrdinal
      )
    } yield dataUpdatesValidation.productR(dataTransactionsValidation)

}
