package io.constellationnetwork.security.signature

import cats.Order
import cats.Order._
import cats.data._
import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.{posIntDecoder, posIntEncoder}
import io.constellationnetwork.security.key.ops.PublicKeyOps
import io.constellationnetwork.security.signature.SignedValidator.SignedValidationErrorOr
import io.constellationnetwork.security.signature.signature.SignatureProof
import io.constellationnetwork.security.{Hasher, SecurityProvider}

import derevo.cats.{order, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import eu.timepit.refined.auto._
import eu.timepit.refined.cats._
import eu.timepit.refined.types.numeric.PosInt
import io.circe.Encoder

trait SignedValidator[F[_]] {

  def validateSignatures[A: Encoder](
    signed: Signed[A]
  )(implicit hasher: Hasher[F]): F[SignedValidationErrorOr[Signed[A]]]

  def validateUniqueSigners[A: Encoder](
    signed: Signed[A]
  ): SignedValidationErrorOr[Signed[A]]

  def validateMinSignatureCount[A: Encoder](
    signed: Signed[A],
    minSignatureCount: PosInt
  ): SignedValidationErrorOr[Signed[A]]

  def isSignedBy[A: Encoder](
    signed: Signed[A],
    signerAddress: Address
  ): F[SignedValidationErrorOr[Signed[A]]]

  def isSignedExclusivelyBy[A: Encoder](
    signed: Signed[A],
    signerAddress: Address
  ): F[SignedValidationErrorOr[Signed[A]]]

  def validateSignaturesWithSeedlist[A <: AnyRef](seedlist: Option[Set[PeerId]], signed: Signed[A]): SignedValidationErrorOr[Signed[A]]

  def validateSignedBySeedlistMajority[A](seedlist: Option[Set[PeerId]], signed: Signed[A]): SignedValidationErrorOr[Signed[A]]
}

object SignedValidator {

  def make[F[_]: Async: SecurityProvider]: SignedValidator[F] = new SignedValidator[F] {

    def validateSignatures[A: Encoder](
      signed: Signed[A]
    )(implicit hasher: Hasher[F]): F[SignedValidationErrorOr[Signed[A]]] =
      signed.validProofs(implicitly[Encoder[A]].asRight).map { either =>
        either
          .leftMap(InvalidSignatures)
          .toValidatedNec
          .map(_ => signed)
      }

    def validateUniqueSigners[A: Encoder](
      signed: Signed[A]
    ): SignedValidationErrorOr[Signed[A]] =
      duplicatedValues(signed.proofs.toNonEmptyList.map(_.id)).toNel
        .map(_.toNes)
        .map(DuplicateSigners)
        .toInvalidNec(signed)

    def validateMinSignatureCount[A: Encoder](
      signed: Signed[A],
      minSignatureCount: PosInt
    ): SignedValidationErrorOr[Signed[A]] =
      if (signed.proofs.size >= minSignatureCount)
        signed.validNec
      else
        NotEnoughSignatures(signed.proofs.size, minSignatureCount).invalidNec

    def isSignedBy[A: Encoder](
      signed: Signed[A],
      signerAddress: Address
    ): F[SignedValidationErrorOr[Signed[A]]] =
      signed.proofs
        .existsM(_.id.toAddress.map(_ === signerAddress))
        .map(Validated.condNec(_, signed, NotSignedByAddressOwner))

    def isSignedExclusivelyBy[A: Encoder](
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

    def validateSignedBySeedlistMajority[A](seedlist: Option[Set[PeerId]], signed: Signed[A]): SignedValidationErrorOr[Signed[A]] =
      seedlist match {
        case None => signed.validNec
        case Some(peers) =>
          val minRequired = peers.size / 2 + 1
          val signingPeers = signed.proofs
            .map(_.id.toPeerId)
            .toSortedSet
            .intersect(peers)

          Validated.condNec(
            signingPeers.size >= minRequired,
            signed,
            NotEnoughSeedlistSignatures(signingPeers.size, minRequired)
          )
      }

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
  case class NotEnoughSeedlistSignatures(signatureCount: Int, minSignatureCount: Int) extends SignedValidationError
  case class DuplicateSigners(signers: NonEmptySet[Id]) extends SignedValidationError
  case class MissingSigners(signers: NonEmptySet[Id]) extends SignedValidationError
  case class SignersNotInSeedlist(signers: NonEmptySet[Id]) extends SignedValidationError
  case object NotSignedExclusivelyByAddressOwner extends SignedValidationError
  case object NotSignedByAddressOwner extends SignedValidationError

  type SignedValidationErrorOr[A] = ValidatedNec[SignedValidationError, A]
}
