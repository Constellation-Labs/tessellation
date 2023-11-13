package org.tessellation.sdk.infrastructure.gossip

import cats.data.NonEmptySet._
import cats.data.{NonEmptySet, Validated, ValidatedNec}
import cats.effect.Async
import cats.syntax.all._

import org.tessellation.ext.cats.syntax.validated._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.ID.Id
import org.tessellation.schema.gossip._
import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.domain.seedlist.SeedlistEntry
import org.tessellation.sdk.infrastructure.gossip.RumorValidator.RumorValidationErrorOr
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.SignedValidator.SignedValidationError
import org.tessellation.security.signature.{Signed, SignedValidator}

import derevo.cats.{eqv, show}
import derevo.derive

trait RumorValidator[F[_]] {

  def validate(signedRumor: Signed[RumorRaw]): F[RumorValidationErrorOr[Signed[RumorRaw]]]

}

object RumorValidator {

  def make[F[_]: Async: KryoSerializer](
    seedlist: Option[Set[SeedlistEntry]],
    signedValidator: SignedValidator[F]
  ): RumorValidator[F] = new RumorValidator[F] {

    def validate(
      signedRumor: Signed[RumorRaw]
    ): F[RumorValidationErrorOr[Signed[RumorRaw]]] =
      validateSignature(signedRumor).map { signatureV =>
        signatureV
          .productR(validateOrigin(signedRumor))
          .productR(validateSeedlist(signedRumor))
      }

    def validateOrigin(signedRumor: Signed[RumorRaw]): RumorValidationErrorOr[Signed[RumorRaw]] =
      signedRumor.value match {
        case _: CommonRumorRaw => signedRumor.validNec[RumorValidationError]
        case rumor: PeerRumorRaw =>
          val signers = signedRumor.proofs.map(_.id)
          Validated.condNec(
            signers.contains(rumor.origin.toId),
            signedRumor,
            NotSignedByOrigin(rumor.origin, signers)
          )
      }

    def validateSignature(signedRumor: Signed[RumorRaw]): F[RumorValidationErrorOr[Signed[RumorRaw]]] =
      signedValidator.validateSignatures(signedRumor).map(_.errorMap(InvalidSigned))

    def validateSeedlist(signedRumor: Signed[RumorRaw]): RumorValidationErrorOr[Signed[RumorRaw]] =
      signedValidator
        .validateSignaturesWithSeedlist(seedlist.map(_.map(_.peerId)), signedRumor)
        .errorMap(SignersNotInSeedlist)

  }

  @derive(eqv, show)
  sealed trait RumorValidationError
  case class InvalidHash(calculatedHash: Hash, receivedHash: Hash) extends RumorValidationError
  case class InvalidSigned(error: SignedValidationError) extends RumorValidationError
  case class SignersNotInSeedlist(error: SignedValidationError) extends RumorValidationError
  case class NotSignedByOrigin(origin: PeerId, signers: NonEmptySet[Id]) extends RumorValidationError

  type RumorValidationErrorOr[A] = ValidatedNec[RumorValidationError, A]

}
