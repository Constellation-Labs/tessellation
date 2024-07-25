package io.constellationnetwork.currency.validations

import cats.data.ValidatedNec
import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.DataTransaction.DataTransactions
import io.constellationnetwork.currency.dataApplication.Errors._
import io.constellationnetwork.currency.dataApplication._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Balance
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed

object FeeTransactionValidator {

  private def validateFeeTransactionHashMatch[F[_]: Async](
    feeTransaction: Signed[FeeTransaction],
    dataTransactions: DataTransactions,
    dataApplication: BaseDataApplicationService[F]
  ): F[ValidatedNec[DataApplicationValidationError, Unit]] =
    dataTransactions.existsM {
      case Signed(dataUpdate: DataUpdate, _) =>
        dataApplication.serializeUpdate(dataUpdate).map { serializedUpdate =>
          Hash.fromBytes(serializedUpdate) === feeTransaction.dataUpdateRef
        }
      case _ => false.pure
    }.ifM(
      ().validNec[DataApplicationValidationError].pure,
      MissingDataUpdateOfFeeTransaction
        .asInstanceOf[DataApplicationValidationError]
        .invalidNec[Unit]
        .pure
    )

  private def validateSourceWalletHasEnoughBalance[F[_]: Async](
    feeTransaction: Signed[FeeTransaction],
    balances: Map[Address, Balance]
  ): ValidatedNec[DataApplicationValidationError, Unit] = {
    val sourceWallet = feeTransaction.value.source
    val balance = Balance.toAmount(balances.getOrElse(sourceWallet, Balance.empty))

    if (balance < feeTransaction.value.amount) {
      SourceWalletNotEnoughBalance
        .asInstanceOf[DataApplicationValidationError]
        .invalidNec
    } else {
      ().validNec[DataApplicationValidationError]
    }
  }

  private def validateSourceWalletSignedFeeTransaction[F[_]: Async: SecurityProvider](
    feeTransaction: Signed[FeeTransaction]
  ): F[ValidatedNec[DataApplicationValidationError, Unit]] =
    feeTransaction.proofs
      .map(_.id)
      .toList
      .traverse(_.toAddress[F])
      .map { proofAddresses =>
        if (proofAddresses.contains(feeTransaction.value.source)) {
          ().validNec[DataApplicationValidationError]
        } else {
          SourceWalletNotSignTheTransaction
            .asInstanceOf[DataApplicationValidationError]
            .invalidNec[Unit]
        }
      }

  def validateFeeTransaction[F[_]: Async: SecurityProvider](
    maybeFeeTransaction: Option[Signed[FeeTransaction]],
    dataTransactions: DataTransactions,
    balances: Map[Address, Balance],
    dataApplication: BaseDataApplicationService[F]
  ): F[ValidatedNec[DataApplicationValidationError, Unit]] =
    maybeFeeTransaction match {
      case None => ().validNec[DataApplicationValidationError].pure
      case Some(feeTransaction) =>
        for {
          sourceWalletValidation <- validateSourceWalletSignedFeeTransaction(feeTransaction)
          balanceValidation = validateSourceWalletHasEnoughBalance(feeTransaction, balances)
          hashMatchValidation <- validateFeeTransactionHashMatch(feeTransaction, dataTransactions, dataApplication)
        } yield sourceWalletValidation.productR(hashMatchValidation).productR(balanceValidation)
    }

}
