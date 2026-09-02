package com.my.project_template.shared_data.validations

import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.DataApplicationValidationError
import io.constellationnetwork.currency.dataApplication.dataApplication.DataApplicationValidationErrorOr
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.signature.Signed

import com.my.project_template.shared_data.types.Types._

object SharedValidations {

  case object MultipleSignatures extends DataApplicationValidationError {
    val message: String = "Only single-signature updates are supported"
  }

  case object SignerIsNotDeclaredAddress extends DataApplicationValidationError {
    val message: String = "The update's declared address does not match the address recovered from its signature"
  }

  case object SpendLegMissingAllowSpendRef extends DataApplicationValidationError {
    val message: String =
      "Spend transactions in a data update must reference a signed allow spend; " +
        "spending the metagraph's own balance from user input is not allowed"
  }

  case object SpendLegSourceMismatch extends DataApplicationValidationError {
    val message: String = "Spend transaction source must be the update's signer"
  }

  /** Binds the declared actor to a proof: the update must carry exactly one signature, and the address derived from that signature must be
    * the declared `update.address`. Without this check `update.address` is an unauthenticated claim that any keypair can make about any
    * address.
    */
  def signerIsDeclaredAddress[F[_]: Async: SecurityProvider](
    signed: Signed[UsageUpdate]
  ): F[DataApplicationValidationErrorOr[Unit]] =
    if (signed.proofs.size =!= 1)
      (MultipleSignatures: DataApplicationValidationError).invalidNec[Unit].pure[F]
    else
      signed.proofs.head.id.toAddress.map { signerAddress =>
        if (signerAddress === signed.value.address)
          ().validNec[DataApplicationValidationError]
        else
          (SignerIsNotDeclaredAddress: DataApplicationValidationError).invalidNec[Unit]
      }

  /** Every user-supplied spend leg must spend the signer's own funds via a signed allow spend. Legs with `allowSpendRef = None` draw on the
    * metagraph's own balance, and the global-layer validator deliberately does not constrain their destination - so a metagraph must never
    * accept such legs from user input, only construct them itself from validated state.
    *
    * This sees only the update value (no proofs), so it is safe to run on both layers.
    */
  def spendLegsAreAllowanceBackedBySigner(
    update: UsageUpdate
  ): DataApplicationValidationErrorOr[Unit] =
    update match {
      case u: UsageUpdateWithSpendTransaction =>
        if (u.spendTransaction.allowSpendRef.isEmpty)
          (SpendLegMissingAllowSpendRef: DataApplicationValidationError).invalidNec[Unit]
        else if (u.spendTransaction.source =!= u.address)
          (SpendLegSourceMismatch: DataApplicationValidationError).invalidNec[Unit]
        else
          ().validNec[DataApplicationValidationError]
      case _ =>
        ().validNec[DataApplicationValidationError]
    }
}
