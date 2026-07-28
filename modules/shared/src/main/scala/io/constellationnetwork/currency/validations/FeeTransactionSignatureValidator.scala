package io.constellationnetwork.currency.validations

import cats.data.{NonEmptySet, ValidatedNec}
import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.SortedSet

import io.constellationnetwork.currency.dataApplication.FeeTransaction
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.signature.{SignatureProof, verifySignatureProof}

import derevo.cats.{eqv, show}
import derevo.derive

object FeeTransactionSignatureValidator {

  val MaxProofCount: Long = 16L

  def isEnabled(globalSnapshotOrdinal: SnapshotOrdinal, activationOrdinal: SnapshotOrdinal): Boolean =
    globalSnapshotOrdinal >= activationOrdinal

  def validate[F[_]: Async: JsonSerializer: SecurityProvider](
    signedTransaction: Signed[FeeTransaction]
  ): F[FeeTransactionSignatureValidationResult[Signed[FeeTransaction]]] = {
    val proofCount = signedTransaction.proofs.size

    if (proofCount > MaxProofCount)
      TooManyProofs(proofCount, MaxProofCount)
        .asInstanceOf[FeeTransactionSignatureValidationError]
        .invalidNec[Signed[FeeTransaction]]
        .pure[F]
    else
      duplicatedSignerIds(signedTransaction).fold {
        hasSourceSignature(signedTransaction).flatMap {
          case false =>
            SourceNotSigned
              .asInstanceOf[FeeTransactionSignatureValidationError]
              .invalidNec[Signed[FeeTransaction]]
              .pure[F]
          case true =>
            validateAllProofs(signedTransaction)
        }
      } { duplicateIds =>
        DuplicateSigners(duplicateIds)
          .asInstanceOf[FeeTransactionSignatureValidationError]
          .invalidNec[Signed[FeeTransaction]]
          .pure[F]
      }
  }

  private def duplicatedSignerIds(
    signedTransaction: Signed[FeeTransaction]
  ): Option[NonEmptySet[Id]] = {
    val duplicatedIds = signedTransaction.proofs.toNonEmptyList
      .map(_.id)
      .toList
      .groupBy(identity)
      .collect {
        case (id, occurrences) if occurrences.sizeIs > 1 => id
      }

    NonEmptySet.fromSet(SortedSet.from(duplicatedIds))
  }

  private def hasSourceSignature[F[_]: Async: SecurityProvider](
    signedTransaction: Signed[FeeTransaction]
  ): F[Boolean] =
    signedTransaction.proofs.toNonEmptyList.existsM { proof =>
      proof.id.toAddress[F].attempt.map(_.contains(signedTransaction.value.source))
    }

  private def validateAllProofs[F[_]: Async: JsonSerializer: SecurityProvider](
    signedTransaction: Signed[FeeTransaction]
  ): F[FeeTransactionSignatureValidationResult[Signed[FeeTransaction]]] =
    FeeTransaction.serialize[F](signedTransaction.value).map(Hash.fromBytes).flatMap { hash =>
      signedTransaction.proofs.toNonEmptyList.traverse { proof =>
        verifySignatureProof[F](hash, proof).map(proof -> _)
      }
        .map(_.collect { case (proof, false) => proof })
        .map { invalidProofs =>
          NonEmptySet
            .fromSet(SortedSet.from(invalidProofs))
            .map(InvalidSignatures(_).asInstanceOf[FeeTransactionSignatureValidationError].invalidNec[Signed[FeeTransaction]])
            .getOrElse(signedTransaction.validNec[FeeTransactionSignatureValidationError])
        }
    }

  @derive(eqv, show)
  sealed trait FeeTransactionSignatureValidationError

  case class TooManyProofs(proofCount: Long, maxProofCount: Long) extends FeeTransactionSignatureValidationError
  case class DuplicateSigners(signers: NonEmptySet[Id]) extends FeeTransactionSignatureValidationError
  case object SourceNotSigned extends FeeTransactionSignatureValidationError
  case class InvalidSignatures(proofs: NonEmptySet[SignatureProof]) extends FeeTransactionSignatureValidationError

  type FeeTransactionSignatureValidationResult[A] = ValidatedNec[FeeTransactionSignatureValidationError, A]
}
