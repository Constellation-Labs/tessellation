package org.tessellation.schema.security.signature

import cats.Order
import cats.data.NonEmptySet._
import cats.data.{NonEmptySet, ValidatedNec}
import cats.effect.Async
import cats.syntax.all._

import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.ID.Id
import org.tessellation.schema.address.Address
import org.tessellation.schema.security.SecurityProvider
import org.tessellation.schema.security.key.ops.PublicKeyOps
import org.tessellation.schema.security.signature.SignedValidator.SignedValidationErrorOr
import org.tessellation.schema.security.signature.signature.SignatureProof

import derevo.cats.{order, show}
import derevo.derive
import eu.timepit.refined.auto._
import eu.timepit.refined.cats._
import eu.timepit.refined.types.numeric.PosInt

trait SignedValidator[F[_]] {

  def validateSignatures[A <: AnyRef](
    signed: Signed[A]
  ): F[SignedValidationErrorOr[Signed[A]]]

  def validateUniqueSigners[A <: AnyRef](
    signed: Signed[A]
  ): SignedValidationErrorOr[Signed[A]]

  def validateMinSignatureCount[A <: AnyRef](
    signed: Signed[A],
    minSignatureCount: PosInt
  ): SignedValidationErrorOr[Signed[A]]

  def isSignedExclusivelyBy[A <: AnyRef](
    signed: Signed[A],
    minSignatureCount: Address
  ): F[SignedValidationErrorOr[Signed[A]]]

}

object SignedValidator {

  def make[F[_]: Async: KryoSerializer: SecurityProvider]: SignedValidator[F] = new SignedValidator[F] {

    def validateSignatures[A <: AnyRef](
      signed: Signed[A]
    ): F[SignedValidationErrorOr[Signed[A]]] =
      signed.validProofs.map { either =>
        either
          .leftMap(InvalidSignatures)
          .toValidatedNec
          .map(_ => signed)
      }

    def validateUniqueSigners[A <: AnyRef](
      signed: Signed[A]
    ): SignedValidationErrorOr[Signed[A]] =
      duplicatedValues(signed.proofs.map(_.id)).toNel
        .map(_.toNes)
        .map(DuplicateSigners)
        .toInvalidNec(signed)

    def validateMinSignatureCount[A <: AnyRef](
      signed: Signed[A],
      minSignatureCount: PosInt
    ): SignedValidationErrorOr[Signed[A]] =
      if (signed.proofs.size >= minSignatureCount)
        signed.validNec
      else
        NotEnoughSignatures(signed.proofs.size, minSignatureCount).invalidNec

    def isSignedExclusivelyBy[A <: AnyRef](
      signed: Signed[A],
      signerAddress: Address
    ): F[SignedValidationErrorOr[Signed[A]]] =
      signed.proofs.existsM { proof =>
        proof.id.hex.toPublicKey.map { signerPk =>
          signerPk.toAddress =!= signerAddress
        }
      }.ifM(
        NotSignedExclusivelyByAddressOwner
          .asInstanceOf[SignedValidationError]
          .invalidNec[Signed[A]]
          .pure[F],
        signed.validNec[SignedValidationError].pure[F]
      )

    private def duplicatedValues[B: Order](values: NonEmptySet[B]): List[B] =
      values.groupBy(identity).toNel.toList.mapFilter {
        case (value, occurrences) =>
          if (occurrences.tail.nonEmpty)
            value.some
          else
            none
      }
  }

  @derive(order, show)
  sealed trait SignedValidationError
  case class InvalidSignatures(invalidSignatures: NonEmptySet[SignatureProof]) extends SignedValidationError
  case class NotEnoughSignatures(signatureCount: Long, minSignatureCount: PosInt) extends SignedValidationError
  case class DuplicateSigners(signers: NonEmptySet[Id]) extends SignedValidationError
  case class MissingSigners(signers: NonEmptySet[Id]) extends SignedValidationError
  case object NotSignedExclusivelyByAddressOwner extends SignedValidationError

  type SignedValidationErrorOr[A] = ValidatedNec[SignedValidationError, A]
}
