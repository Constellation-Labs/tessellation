package org.tessellation.currency.validations

import cats.data.{NonEmptyList, OptionT, ValidatedNec}
import cats.effect.Async
import cats.syntax.all._

import org.tessellation.currency.dataApplication.DataTransaction.DataTransactions
import org.tessellation.currency.dataApplication.DataUpdate.getDataUpdates
import org.tessellation.currency.dataApplication.Errors.MissingDataUpdateTransaction
import org.tessellation.currency.dataApplication.FeeTransaction.getByDataUpdate
import org.tessellation.currency.dataApplication._
import org.tessellation.currency.validations.FeeTransactionValidator.validateFeeTransaction
import org.tessellation.schema.SnapshotOrdinal
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Balance
import org.tessellation.security.SecurityProvider
import org.tessellation.security.signature.Signed

object DataTransactionsValidator {
  private def validateDataTransactions[F[_]: Async: SecurityProvider](
    dataTransactions: DataTransactions,
    balances: Map[Address, Balance],
    dataApplication: BaseDataApplicationService[F],
    validateFee: SnapshotOrdinal => (
      Signed[DataUpdate],
      Option[Signed[FeeTransaction]]
    ) => F[ValidatedNec[DataApplicationValidationError, Unit]],
    gsOrdinal: SnapshotOrdinal
  ): F[ValidatedNec[DataApplicationValidationError, Unit]] = {

    val dataUpdates = dataTransactions.collect {
      case Signed(dataUpdate: DataUpdate, proofs) => Signed(dataUpdate, proofs)
    }
    NonEmptyList.fromList(dataUpdates) match {
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
        gsOrdinal
      )
    } yield dataUpdatesValidation.productR(dataTransactionsValidation)

  def validateDataTransactionsL0[F[_]: Async: L0NodeContext: SecurityProvider](
    dataTransactions: DataTransactions,
    dataApplication: BaseDataApplicationL0Service[F],
    balances: Map[Address, Balance],
    gsOrdinal: SnapshotOrdinal,
    currentState: DataState[DataOnChainState, DataCalculatedState]
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
        gsOrdinal
      )
    } yield dataUpdatesValidation.productR(dataTransactionsValidation)

}
