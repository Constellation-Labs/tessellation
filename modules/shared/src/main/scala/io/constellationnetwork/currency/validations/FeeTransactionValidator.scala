package io.constellationnetwork.currency.validations

import cats.data.Validated.{Invalid, Valid}
import cats.data.ValidatedNec
import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.DataTransaction.DataTransactions
import io.constellationnetwork.currency.dataApplication.Errors._
import io.constellationnetwork.currency.dataApplication._
import io.constellationnetwork.json.JsonSerializer
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

  // Legacy path, kept for replay below the activation ordinal. It reaches a fee transaction only through
  // getByDataUpdate, so it sees at most one per data update; validateAllFeeTransactions is the current path.
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

  private def validateFeeTransactionRefPresent(
    feeTransaction: Signed[FeeTransaction],
    dataUpdateHashes: Set[Hash]
  ): ValidatedNec[DataApplicationValidationError, Unit] =
    if (dataUpdateHashes.contains(feeTransaction.value.dataUpdateRef)) ().validNec[DataApplicationValidationError]
    else
      MissingDataUpdateOfFeeTransaction
        .asInstanceOf[DataApplicationValidationError]
        .invalidNec[Unit]

  // node-shared's FeeTransactionValidator runs on the same transactions immediately before acceptance and
  // enforces both exclusivity and source != destination. Anything it rejects but this layer accepts is a
  // user-supplied value that reaches acceptance and is dropped there, after combine has already applied the
  // data update it was paying for.
  private def validateFeeTransactionAddresses[F[_]: Async: SecurityProvider](
    feeTransaction: Signed[FeeTransaction]
  ): F[ValidatedNec[DataApplicationValidationError, Unit]] = {
    val tx = feeTransaction.value

    feeTransaction.proofs
      .map(_.id)
      .toList
      .traverse(_.toAddress[F])
      .map { proofAddresses =>
        // Exclusivity only makes sense as a complaint once the source has signed at all; reporting both
        // when it has not just duplicates SourceWalletNotSignTheTransaction.
        val signedBySource =
          if (!proofAddresses.contains(tx.source))
            SourceWalletNotSignTheTransaction.asInstanceOf[DataApplicationValidationError].invalidNec[Unit]
          else if (!proofAddresses.forall(_ === tx.source))
            FeeTransactionNotSignedExclusivelyBySource.asInstanceOf[DataApplicationValidationError].invalidNec[Unit]
          else ().validNec[DataApplicationValidationError]

        val differentAddresses =
          if (tx.source =!= tx.destination) ().validNec[DataApplicationValidationError]
          else SameSourceAndDestinationAddress.asInstanceOf[DataApplicationValidationError].invalidNec[Unit]

        signedBySource.productR(differentAddresses)
      }
  }

  // Checks the proofs against the transaction bytes. validateFeeTransactionAddresses covers which wallet each
  // proof names; this covers whether the proof was produced by that wallet's key.
  private def validateFeeTransactionSignatures[F[_]: Async: JsonSerializer: SecurityProvider](
    feeTransaction: Signed[FeeTransaction]
  ): F[ValidatedNec[DataApplicationValidationError, Unit]] =
    FeeTransactionSignatureValidator.validate(feeTransaction).map {
      case Valid(_)   => ().validNec[DataApplicationValidationError]
      case Invalid(_) => InvalidSignature.asInstanceOf[DataApplicationValidationError].invalidNec[Unit]
    }

  // Acceptance applies every fee transaction in the envelope, so every one of them has to be validated
  // here. Walking the data updates instead reaches a fee transaction only through getByDataUpdate, which
  // returns an Option -- a fee transaction referencing no data update present in the envelope is skipped
  // entirely and reaches acceptance unchecked.
  def validateAllFeeTransactions[F[_]: Async: JsonSerializer: SecurityProvider](
    dataTransactions: DataTransactions,
    balances: Map[Address, Balance],
    dataApplication: BaseDataApplicationService[F]
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
          .traverse(dataUpdate => dataApplication.serializeUpdate(dataUpdate).map(Hash.fromBytes))
          .map(_.toSet)

    dataUpdateHashes.flatMap { hashes =>
      feeTransactions.traverse { feeTransaction =>
        // One EC verify per proof, with the proof count coming from the sender, so this runs only after the
        // cheaper checks pass. A transaction failing those is rejected either way.
        validateFeeTransactionAddresses(feeTransaction)
          .map(_.productR(validateFeeTransactionRefPresent(feeTransaction, hashes)))
          .flatMap { cheapChecks =>
            if (cheapChecks.isValid) validateFeeTransactionSignatures(feeTransaction)
            else cheapChecks.pure[F]
          }
      }.map {
        _.foldLeft(().validNec[DataApplicationValidationError])(_.productR(_))
          .productR(validateSourcesHaveEnoughBalance(feeTransactions, balances))
      }
    }
  }

  // A single source may fund several fee transactions in one envelope. Checking each against the same
  // starting balance lets the group overspend it, so the group is summed with the checked Amount.plus.
  //
  // This is deliberately stricter than DataApplicationSnapshotAcceptanceManager.applyFeeTransactions, which
  // folds sequentially and so can pay a fee transaction out of a credit an earlier one in the same block
  // produced. The two do not need to agree: this runs per envelope, applyFeeTransactions runs per block over
  // the block's whole SortedSet, and the fold order there is amount-ascending rather than envelope order.
  // Rejecting an envelope this layer cannot prove affordable costs the sender a resubmission; accepting one
  // it cannot is the failure that matters.
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
