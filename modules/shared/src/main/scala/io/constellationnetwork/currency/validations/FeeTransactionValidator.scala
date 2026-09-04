package io.constellationnetwork.currency.validations

import cats.data.ValidatedNec
import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.DataTransaction.DataTransactions
import io.constellationnetwork.currency.dataApplication.Errors._
import io.constellationnetwork.currency.dataApplication._
import io.constellationnetwork.currency.validations.FeeTransactionSignatureValidator.{
  isEnabled,
  validate => validateFeeTransactionSignatures
}
import io.constellationnetwork.ext.cats.syntax.validated._
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.{Amount, Balance}
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
        dataApplication.serializeUpdate(dataUpdate).flatMap { serializedUpdate =>
          Hash.fromBytesForSync(serializedUpdate).map(_ === feeTransaction.dataUpdateRef)
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

  def validateFeeTransaction[F[_]: Async: JsonSerializer: SecurityProvider](
    maybeFeeTransaction: Option[Signed[FeeTransaction]],
    dataTransactions: DataTransactions,
    balances: Map[Address, Balance],
    dataApplication: BaseDataApplicationService[F],
    globalSnapshotOrdinal: SnapshotOrdinal,
    feeTransactionSecurityActivationOrdinal: SnapshotOrdinal
  ): F[ValidatedNec[DataApplicationValidationError, Unit]] =
    maybeFeeTransaction match {
      case None => ().validNec[DataApplicationValidationError].pure
      case Some(feeTransaction) =>
        for {
          sourceWalletValidation <-
            if (!isEnabled(globalSnapshotOrdinal, feeTransactionSecurityActivationOrdinal))
              validateSourceWalletSignedFeeTransaction(feeTransaction)
            else
              validateFeeTransactionSignatures(feeTransaction)
                .map(_.errorMap[DataApplicationValidationError](_ => InvalidFeeTransactionSignature).void)
          balanceValidation = validateSourceWalletHasEnoughBalance(feeTransaction, balances)
          hashMatchValidation <- validateFeeTransactionHashMatch(feeTransaction, dataTransactions, dataApplication)
        } yield sourceWalletValidation.productR(hashMatchValidation).productR(balanceValidation)
    }

  private def validateFeeTransactionRefPresent(
    feeTransaction: Signed[FeeTransaction],
    dataUpdateHashes: Set[Hash]
  ): ValidatedNec[DataApplicationValidationError, Unit] =
    if (dataUpdateHashes.contains(feeTransaction.value.dataUpdateRef)) ().validNec[DataApplicationValidationError]
    else
      MissingDataUpdateOfFeeTransaction
        .asInstanceOf[DataApplicationValidationError]
        .invalidNec[Unit]

  /** The historical release/mainnet rule required every signer to name the source wallet. The later fee-transaction-security gate permits
    * additional valid signers, but only after the source has also signed and every proof has passed cryptographic verification.
    *
    * Keeping this check inside the data-application validator guarantees that a fee accepted with a data update also passes the final
    * Currency acceptance validator. Otherwise final acceptance can drop the fee after the application has already combined the update.
    */
  private def validateExclusiveSourceWalletProofs[F[_]: Async: SecurityProvider](
    feeTransaction: Signed[FeeTransaction]
  ): F[ValidatedNec[DataApplicationValidationError, Unit]] =
    feeTransaction.proofs.toNonEmptyList
      .traverse(_.id.toAddress[F])
      .map { proofAddresses =>
        if (proofAddresses.forall(_ === feeTransaction.value.source)) ().validNec[DataApplicationValidationError]
        else FeeTransactionNotSignedExclusivelyBySource.asInstanceOf[DataApplicationValidationError].invalidNec[Unit]
      }

  // Acceptance applies every fee transaction in the envelope, so every one of them has to be validated here.
  // Walking the data updates instead reaches a fee transaction only through getByDataUpdate, which returns an
  // Option -- a fee transaction referencing no data update present in the envelope is skipped entirely and
  // reaches acceptance unchecked.
  //
  // Keep the existing three-argument SDK entry point source- and binary-compatible. Network validation uses
  // validateAllFeeTransactionsWithSignerPolicy below so replay selects the policy from its signed Global ordinal.
  def validateAllFeeTransactions[F[_]: Async: JsonSerializer: SecurityProvider](
    dataTransactions: DataTransactions,
    balances: Map[Address, Balance],
    dataApplication: BaseDataApplicationService[F]
  ): F[ValidatedNec[DataApplicationValidationError, Unit]] =
    validateAllFeeTransactionsWithSignerPolicy(
      dataTransactions,
      balances,
      dataApplication,
      allowSourceAuthorizedCoSigners = true
    )

  private[validations] def validateAllFeeTransactionsWithSignerPolicy[F[_]: Async: JsonSerializer: SecurityProvider](
    dataTransactions: DataTransactions,
    balances: Map[Address, Balance],
    dataApplication: BaseDataApplicationService[F],
    allowSourceAuthorizedCoSigners: Boolean
  ): F[ValidatedNec[DataApplicationValidationError, Unit]] = {
    val feeTransactions = dataTransactions.collect {
      case Signed(feeTransaction: FeeTransaction, proofs) => Signed(feeTransaction, proofs)
    }

    // Hashed once for the whole envelope. Checking each fee transaction against the data updates one at a
    // time re-serializes them per fee transaction, which a sender controls: n fee transactions all pointing
    // at a dataUpdateRef that is not there costs every validating node n * m serializations. Envelopes
    // carrying no fee transactions still serialize nothing, which is the common case.
    val dataUpdateHashes: F[Set[Hash]] =
      if (feeTransactions.isEmpty) Set.empty[Hash].pure[F]
      else
        dataTransactions.collect { case Signed(dataUpdate: DataUpdate, _) => dataUpdate }
          .traverse(dataUpdate => dataApplication.serializeUpdate(dataUpdate).flatMap(Hash.fromBytesForSync(_)))
          .map(_.toSet)

    dataUpdateHashes.flatMap { hashes =>
      feeTransactions.traverse { feeTransaction =>
        validateFeeTransactionSignatures(feeTransaction)
          .map(_.errorMap[DataApplicationValidationError](_ => InvalidFeeTransactionSignature).void)
          .flatMap { signatureValidation =>
            val signerPolicyValidation =
              if (signatureValidation.isInvalid || allowSourceAuthorizedCoSigners)
                ().validNec[DataApplicationValidationError].pure[F]
              else validateExclusiveSourceWalletProofs(feeTransaction)

            signerPolicyValidation.map(
              signatureValidation
                .productR(_)
                .productR(validateDifferentAddresses(feeTransaction))
                .productR(validateFeeTransactionRefPresent(feeTransaction, hashes))
            )
          }
      }.map {
        _.foldLeft(().validNec[DataApplicationValidationError])(_.productR(_))
          .productR(validateSourcesHaveEnoughBalance(feeTransactions, balances))
      }
    }
  }

  // node-shared's FeeTransactionValidator runs on the same transactions immediately before acceptance and
  // enforces source != destination. Anything it rejects but this layer accepts is a user-supplied value that
  // reaches acceptance and is dropped there, after combine has already applied the data update it paid for.
  private def validateDifferentAddresses(
    feeTransaction: Signed[FeeTransaction]
  ): ValidatedNec[DataApplicationValidationError, Unit] =
    if (feeTransaction.value.source =!= feeTransaction.value.destination) ().validNec[DataApplicationValidationError]
    else SameSourceAndDestinationAddress.asInstanceOf[DataApplicationValidationError].invalidNec[Unit]

  // A single source may fund several fee transactions in one envelope. Checking each against the same
  // starting balance lets the group overspend it, so the group is summed with the checked Amount.plus.
  //
  // This is deliberately stricter than the per-block fold in DataApplicationSnapshotAcceptanceManager, which
  // can pay a fee transaction out of a credit an earlier one in the same block produced. The two do not need
  // to agree: this runs per envelope and the fold there runs per block in amount-ascending order. Rejecting
  // an envelope this layer cannot prove affordable costs the sender a resubmission; accepting one it cannot
  // is the failure that matters.
  private def validateSourcesHaveEnoughBalance(
    feeTransactions: List[Signed[FeeTransaction]],
    balances: Map[Address, Balance]
  ): ValidatedNec[DataApplicationValidationError, Unit] =
    feeTransactions
      .groupBy(_.value.source)
      .toList
      .traverse {
        case (source, txs) =>
          val notEnoughBalance = SourceWalletNotEnoughBalance.asInstanceOf[DataApplicationValidationError]

          val totalOrError = txs.foldLeft(Amount.empty.asRight[DataApplicationValidationError]) { (acc, tx) =>
            acc.flatMap(_.plus(tx.value.amount).leftMap(_ => notEnoughBalance))
          }

          totalOrError.flatMap { total =>
            val available = Balance.toAmount(balances.getOrElse(source, Balance.empty))

            if (available < total) notEnoughBalance.asLeft[Unit]
            else ().asRight[DataApplicationValidationError]
          }.toValidatedNec
      }
      .void

}
