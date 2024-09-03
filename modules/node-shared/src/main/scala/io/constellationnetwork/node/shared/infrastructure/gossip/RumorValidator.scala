package io.constellationnetwork.node.shared.infrastructure.gossip

import cats.data.NonEmptySet._
import cats.data.{NonEmptySet, Validated, ValidatedNec}
import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.ext.cats.syntax.validated._
import io.constellationnetwork.node.shared.domain.seedlist.SeedlistEntry
import io.constellationnetwork.node.shared.infrastructure.gossip.RumorValidator.RumorValidationErrorOr
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema.gossip._
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.SignedValidator.SignedValidationError
import io.constellationnetwork.security.signature.{Signed, SignedValidator}

import derevo.cats.{eqv, show}
import derevo.derive

trait RumorValidator[F[_]] {

  def validate(signedRumor: Signed[RumorRaw])(implicit hasher: Hasher[F]): F[RumorValidationErrorOr[Signed[RumorRaw]]]

}

object RumorValidator {

  def make[F[_]: Async](
    seedlist: Option[Set[SeedlistEntry]],
    signedValidator: SignedValidator[F]
  ): RumorValidator[F] = new RumorValidator[F] {

    def validate(
      signedRumor: Signed[RumorRaw]
    )(implicit hasher: Hasher[F]): F[RumorValidationErrorOr[Signed[RumorRaw]]] =
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

    def validateSignature(signedRumor: Signed[RumorRaw])(implicit hasher: Hasher[F]): F[RumorValidationErrorOr[Signed[RumorRaw]]] =
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
