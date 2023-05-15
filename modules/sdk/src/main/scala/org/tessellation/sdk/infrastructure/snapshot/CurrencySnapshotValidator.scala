package org.tessellation.sdk.infrastructure.snapshot

import cats.syntax.all._
import cats.data.ValidatedNec
import cats.effect.kernel.Async
import derevo.cats.{eqv, show}
import derevo.derive
import org.tessellation.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencySnapshotInfo}
import org.tessellation.sdk.infrastructure.snapshot.CurrencySnapshotValidator.CurrencySnapshotValidationError
import org.tessellation.security.signature.SignedValidator.SignedValidationError
import org.tessellation.security.signature.{Signed, SignedValidator}

import org.tessellation.ext.cats.syntax.validated._

trait CurrencySnapshotValidator[F[_]] {

  type CurrencySnapshotValidationErrorOr[A] = ValidatedNec[CurrencySnapshotValidationError, A]

  def validateSignedSnapshot(
    lastSnapshot: Signed[CurrencyIncrementalSnapshot],
    lastState: CurrencySnapshotInfo,
    snapshot: Signed[CurrencyIncrementalSnapshot]
  ): F[CurrencySnapshotValidationErrorOr[(Signed[CurrencyIncrementalSnapshot], CurrencySnapshotInfo)]]

}

object CurrencySnapshotValidator {

  def make[F[_]: Async](
    currencyDataCreator: CurrencyDataCreator[F],
    signedValidator: SignedValidator[F]
  ): CurrencySnapshotValidator[F] = new CurrencySnapshotValidator[F] {
    def validateSignedSnapshot(
      lastSnapshot: Signed[CurrencyIncrementalSnapshot],
      lastState: CurrencySnapshotInfo,
      snapshot: Signed[CurrencyIncrementalSnapshot]
    ): F[CurrencySnapshotValidationErrorOr[(Signed[CurrencyIncrementalSnapshot], CurrencySnapshotInfo)]] =
      validateSigned(snapshot).flatMap { signedV =>
        validateContent(snapshot.value, )
      }

    private def validateSigned(
      signedSnapshot: Signed[CurrencyIncrementalSnapshot]
    ): F[CurrencySnapshotValidationErrorOr[Signed[CurrencyIncrementalSnapshot]]] =
      signedValidator.validateSignatures(signedSnapshot).map(_.errorMap(InvalidSigned))

    private def validateContent(
      actual: CurrencyIncrementalSnapshot,
      expected: CurrencyIncrementalSnapshot
    ): CurrencySnapshotValidationErrorOr[CurrencyIncrementalSnapshot] =
      if (actual =!= expected)
        SnapshotDifferentThanExpected(actual, expected).invalidNec
      else
        actual.validNec
  }

  @derive(eqv, show)
  sealed trait CurrencySnapshotValidationError

  case class SnapshotDifferentThanExpected(expected: CurrencyIncrementalSnapshot, actual: CurrencyIncrementalSnapshot)
      extends CurrencySnapshotValidationError
  case class InvalidSigned(error: SignedValidationError) extends CurrencySnapshotValidationError

}
