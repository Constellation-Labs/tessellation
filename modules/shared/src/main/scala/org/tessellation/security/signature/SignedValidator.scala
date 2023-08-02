package org.tessellation.security.signature

import cats.Order
import cats.Order._
import cats.data.NonEmptySet._
import cats.data.{NonEmptyList, NonEmptySet, ValidatedNec}
import cats.effect.Async
import cats.syntax.all._

import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.ID.Id
import org.tessellation.schema.address.Address
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.{posIntDecoder, posIntEncoder}
import org.tessellation.security.SecurityProvider
import org.tessellation.security.key.ops.PublicKeyOps
import org.tessellation.security.signature.SignedValidator.SignedValidationErrorOr
import org.tessellation.security.signature.signature.SignatureProof

import derevo.cats.{order, show}
import derevo.circe.magnolia.{decoder, encoder}
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
    signerAddress: Address
  ): F[SignedValidationErrorOr[Signed[A]]]

  def validateSignaturesWithSeedlist[A <: AnyRef](seedlist: Option[Set[PeerId]], signed: Signed[A]): SignedValidationErrorOr[Signed[A]]

}

object SignedValidator {

  def make[F[_]: Async: KryoSerializer: SecurityProvider]: SignedValidator[F] = new SignedValidator[F] {

    def validateSignatures[A <: AnyRef](
      signed: Signed[A]
    ): F[SignedValidationErrorOr[Signed[A]]] =
      signed.validProofs(None).map { either =>
        either
          .leftMap(InvalidSignatures)
          .toValidatedNec
          .map(_ => signed)
      }

    def validateUniqueSigners[A <: AnyRef](
      signed: Signed[A]
    ): SignedValidationErrorOr[Signed[A]] =
      duplicatedValues(signed.proofs.toNonEmptyList.map(_.id)).toNel
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

    def validateSignaturesWithSeedlist[A <: AnyRef](seedlist: Option[Set[PeerId]], signed: Signed[A]): SignedValidationErrorOr[Signed[A]] =
      seedlist.flatMap { peers =>
        signed.proofs
          .map(_.id.toPeerId)
          .toSortedSet
          .diff(peers)
          .map(_.toId)
          .toNes
      }.map(SignersNotInSeedlist).toInvalidNec(signed)

    private def duplicatedValues[B: Order](values: NonEmptyList[B]): List[B] =
      values.groupBy(identity).toList.mapFilter {
        case (value, occurrences) =>
          if (occurrences.tail.nonEmpty)
            value.some
          else
            none
      }
  }

  @derive(order, show, decoder, encoder)
  sealed trait SignedValidationError
  case class InvalidSignatures(invalidSignatures: NonEmptySet[SignatureProof]) extends SignedValidationError
  case class NotEnoughSignatures(signatureCount: Long, minSignatureCount: PosInt) extends SignedValidationError
  case class DuplicateSigners(signers: NonEmptySet[Id]) extends SignedValidationError
  case class MissingSigners(signers: NonEmptySet[Id]) extends SignedValidationError
  case class SignersNotInSeedlist(signers: NonEmptySet[Id]) extends SignedValidationError
  case object NotSignedExclusivelyByAddressOwner extends SignedValidationError

  type SignedValidationErrorOr[A] = ValidatedNec[SignedValidationError, A]
}
