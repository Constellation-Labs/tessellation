package org.tessellation.sdk.infrastructure.gossip

import cats.Order._
import cats.data.NonEmptySet._
import cats.data.{NonEmptySet, Validated, ValidatedNec}
import cats.effect.Async
import cats.syntax.all._

import org.tessellation.ext.cats.syntax.validated._
import org.tessellation.ext.crypto._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.ID.Id
import org.tessellation.schema.gossip._
import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.infrastructure.gossip.RumorValidator.RumorValidationErrorOr
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.SignedValidator.SignedValidationError
import org.tessellation.security.signature.{Signed, SignedValidator}

import derevo.cats.{eqv, show}
import derevo.derive

trait RumorValidator[F[_]] {

  def validate(har: HashAndRumor): F[RumorValidationErrorOr[HashAndRumor]]

}

object RumorValidator {

  def make[F[_]: Async: KryoSerializer](
    seedlist: Option[Set[PeerId]],
    signedValidator: SignedValidator[F]
  ): RumorValidator[F] = new RumorValidator[F] {

    def validate(
      har: HashAndRumor
    ): F[RumorValidationErrorOr[HashAndRumor]] = har match {
      case (hash, signedRumor) =>
        for {
          hashV <- validateHash(hash, signedRumor)
          originV = validateOrigin(signedRumor)
          signatureV <- validateSignature(signedRumor)
          seedlistV = validateSeedlist(signedRumor)
        } yield
          hashV
            .productL(originV)
            .productL(signatureV)
            .productL(seedlistV)
    }

    def validateHash(hash: Hash, signedRumor: Signed[RumorBinary]): F[RumorValidationErrorOr[HashAndRumor]] =
      signedRumor.value.hashF.map { calculatedHash =>
        Validated.condNec(calculatedHash === hash, (hash, signedRumor), InvalidHash(calculatedHash, hash))
      }

    def validateOrigin(signedRumor: Signed[RumorBinary]): RumorValidationErrorOr[Signed[RumorBinary]] =
      signedRumor.value match {
        case _: CommonRumorBinary => signedRumor.validNec[RumorValidationError]
        case rumor: PeerRumorBinary =>
          val signers = signedRumor.proofs.map(_.id)
          Validated.condNec(
            signers.contains(rumor.origin.toId),
            signedRumor,
            NotSignedByOrigin(rumor.origin, signers)
          )
      }

    def validateSignature(signedRumor: Signed[RumorBinary]): F[RumorValidationErrorOr[Signed[RumorBinary]]] =
      signedValidator.validateSignatures(signedRumor).map(_.errorMap(InvalidSigned))

    def validateSeedlist(signedRumor: Signed[RumorBinary]): RumorValidationErrorOr[Signed[RumorBinary]] =
      seedlist.flatMap { peers =>
        signedRumor.proofs
          .map(_.id.toPeerId)
          .toSortedSet
          .diff(peers)
          .map(_.toId)
          .toNes
      }.map(SignersNotInSeedlist).toInvalidNec(signedRumor)

  }

  @derive(eqv, show)
  sealed trait RumorValidationError
  case class InvalidHash(calculatedHash: Hash, receivedHash: Hash) extends RumorValidationError
  case class InvalidSigned(error: SignedValidationError) extends RumorValidationError
  case class SignersNotInSeedlist(signers: NonEmptySet[Id]) extends RumorValidationError
  case class NotSignedByOrigin(origin: PeerId, signers: NonEmptySet[Id]) extends RumorValidationError

  type RumorValidationErrorOr[A] = ValidatedNec[RumorValidationError, A]

}
