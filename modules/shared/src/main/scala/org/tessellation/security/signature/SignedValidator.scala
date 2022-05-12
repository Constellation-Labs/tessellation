package org.tessellation.security.signature

import cats.Order
import cats.data.{NonEmptyList, ValidatedNec}
import cats.effect.Async
import cats.syntax.all._

import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.ID.Id
import org.tessellation.security.SecurityProvider
import org.tessellation.security.signature.SignedValidator.SignedValidationErrorOr
import org.tessellation.security.signature.signature.SignatureProof

import derevo.cats.{eqv, show}
import derevo.derive
import eu.timepit.refined.auto._
import eu.timepit.refined.cats._
import eu.timepit.refined.types.numeric.PosInt

trait SignedValidator[F[_]] {

  def validateSignatures[A <: AnyRef](
    signedBlock: Signed[A]
  ): F[SignedValidationErrorOr[Signed[A]]]

  def validateUniqueSigners[A <: AnyRef](
    signedBlock: Signed[A]
  ): SignedValidationErrorOr[Signed[A]]

  def validateMinSignatureCount[A <: AnyRef](
    signedBlock: Signed[A],
    minSignatureCount: PosInt
  ): SignedValidationErrorOr[Signed[A]]

}

object SignedValidator {

  def make[F[_]: Async: KryoSerializer: SecurityProvider]: SignedValidator[F] = new SignedValidator[F] {

    def validateSignatures[A <: AnyRef](
      signedBlock: Signed[A]
    ): F[SignedValidationErrorOr[Signed[A]]] =
      signedBlock.validProofs.map { either =>
        either
          .leftMap(InvalidSignatures)
          .toValidatedNec
          .map(_ => signedBlock)
      }

    def validateUniqueSigners[A <: AnyRef](
      signedBlock: Signed[A]
    ): SignedValidationErrorOr[Signed[A]] =
      duplicatedValues(signedBlock.proofs.map(_.id)).toNel
        .map(DuplicateSigners)
        .toInvalidNec(signedBlock)

    def validateMinSignatureCount[A <: AnyRef](
      signedBlock: Signed[A],
      minSignatureCount: PosInt
    ): SignedValidationErrorOr[Signed[A]] =
      if (signedBlock.proofs.size >= minSignatureCount)
        signedBlock.validNec
      else
        NotEnoughSignatures(signedBlock.proofs.size, minSignatureCount).invalidNec

    private def duplicatedValues[B: Order](values: NonEmptyList[B]): List[B] =
      values.groupBy(identity).toList.mapFilter {
        case (value, occurrences) =>
          if (occurrences.tail.nonEmpty)
            value.some
          else
            none
      }
  }

  @derive(eqv, show)
  sealed trait SignedValidationError
  case class InvalidSignatures(invalidSignatures: NonEmptyList[SignatureProof]) extends SignedValidationError
  case class NotEnoughSignatures(signatureCount: Int, minSignatureCount: PosInt) extends SignedValidationError
  case class DuplicateSigners(signers: NonEmptyList[Id]) extends SignedValidationError
  case class MissingSigners(signers: NonEmptyList[Id]) extends SignedValidationError

  type SignedValidationErrorOr[A] = ValidatedNec[SignedValidationError, A]
}
