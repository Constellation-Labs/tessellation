package org.tessellation.sdk.infrastructure.snapshot

import cats.syntax.all._
import cats.data.ValidatedNec
import cats.effect.kernel.Async
import derevo.cats.{eqv, show}
import derevo.derive
import org.tessellation.schema.{Block, GlobalIncrementalSnapshot, GlobalSnapshotInfo}
import org.tessellation.sdk.infrastructure.snapshot.GlobalSnapshotCreator.GlobalSnapshotCreationResult
import org.tessellation.sdk.infrastructure.snapshot.GlobalSnapshotValidator.GlobalSnapshotValidationError
import org.tessellation.security.signature.SignedValidator.SignedValidationError
import org.tessellation.security.signature.{Signed, SignedValidator}
import org.tessellation.statechannel.StateChannelOutput

import org.tessellation.ext.cats.syntax.validated._

trait GlobalSnapshotValidator[F[_]] {

  type GlobalSnapshotValidationErrorOr[A] =
    ValidatedNec[GlobalSnapshotValidationError, A]

  def validateSnapshot(
    lastSnapshot: Signed[GlobalIncrementalSnapshot],
    lastState: GlobalSnapshotInfo,
    snapshot: GlobalIncrementalSnapshot
  ): F[GlobalSnapshotValidationErrorOr[(GlobalIncrementalSnapshot, GlobalSnapshotInfo)]]

  def validateSignedSnapshot(
    lastSnapshot: Signed[GlobalIncrementalSnapshot],
    lastState: GlobalSnapshotInfo,
    signedSnapshot: Signed[GlobalIncrementalSnapshot]
  ): F[GlobalSnapshotValidationErrorOr[(Signed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)]]

}

object GlobalSnapshotValidator {

  def make[F[_]: Async](
    creator: GlobalSnapshotCreator[F],
    signedValidator: SignedValidator[F]
  ): GlobalSnapshotValidator[F] =
    new GlobalSnapshotValidator[F] {
      def validateSignedSnapshot(
        lastSnapshot: Signed[GlobalIncrementalSnapshot],
        lastState: GlobalSnapshotInfo,
        signedSnapshot: Signed[GlobalIncrementalSnapshot]
      ): F[GlobalSnapshotValidationErrorOr[(Signed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)]] =
        validateSigned(signedSnapshot).flatMap { signedV =>
          validateSnapshot(lastSnapshot, lastState, signedSnapshot.value).map { snapshotV =>
            signedV.product(snapshotV.map { case (_, info) => info })
          }
        }

      def validateSnapshot(
        lastSnapshot: Signed[GlobalIncrementalSnapshot],
        lastState: GlobalSnapshotInfo,
        snapshot: GlobalIncrementalSnapshot
      ): F[GlobalSnapshotValidationErrorOr[(GlobalIncrementalSnapshot, GlobalSnapshotInfo)]] = {
        val blocks = lastSnapshot.value.currencyData.blocks.map(_.block)
        val stateChannelSnapshots = snapshot.stateChannelSnapshots.toSet.flatMap {
          case (address, stateChannelBinaries) => stateChannelBinaries.map(StateChannelOutput(address, _)).toList
        }
        val mintRewards = snapshot.currencyData.epochProgress > lastSnapshot.value.currencyData.epochProgress

        creator
          .createGlobalSnapshot(
            lastSnapshot,
            lastState,
            blocks,
            stateChannelSnapshots,
            mintRewards
          )
          .map { creationResult =>
            validateContent(snapshot, creationResult.snapshot)
              .productL(validateNotAcceptedBlocks(creationResult))
              .productL(validateNotAcceptedStateChannelSnapshots(creationResult))
              .map(snapshot => (snapshot, creationResult.state))
          }
      }

      private def validateSigned(
        signedSnapshot: Signed[GlobalIncrementalSnapshot]
      ): F[GlobalSnapshotValidationErrorOr[Signed[GlobalIncrementalSnapshot]]] =
        signedValidator.validateSignatures(signedSnapshot).map(_.errorMap(InvalidSigned))

      private def validateContent(
        actual: GlobalIncrementalSnapshot,
        expected: GlobalIncrementalSnapshot
      ): GlobalSnapshotValidationErrorOr[GlobalIncrementalSnapshot] =
        if (actual =!= expected)
          SnapshotDifferentThanExpected(actual, expected).invalidNec
        else
          actual.validNec

      private def validateNotAcceptedBlocks(
        creationResult: GlobalSnapshotCreationResult
      ): GlobalSnapshotValidationErrorOr[Unit] =
        if (creationResult.awaitingBlocks.nonEmpty || creationResult.rejectedBlocks.nonEmpty)
          SomeBlocksWereNotAccepted(creationResult.awaitingBlocks, creationResult.rejectedBlocks).invalidNec
        else
          ().validNec

      private def validateNotAcceptedStateChannelSnapshots(
        creationResult: GlobalSnapshotCreationResult
      ): GlobalSnapshotValidationErrorOr[Unit] =
        if (creationResult.awaitingStateChannelSnapshots.nonEmpty)
          SomeStateChannelSnapshotsWereNotAccepted(creationResult.awaitingStateChannelSnapshots).invalidNec
        else
          ().validNec
    }

  @derive(eqv, show)
  sealed trait GlobalSnapshotValidationError

  case class SnapshotDifferentThanExpected(actual: GlobalIncrementalSnapshot, expected: GlobalIncrementalSnapshot)
      extends GlobalSnapshotValidationError
  case class SomeBlocksWereNotAccepted(awaitingBlocks: Set[Signed[Block]], rejectedBlocks: Set[Signed[Block]])
      extends GlobalSnapshotValidationError
  case class SomeStateChannelSnapshotsWereNotAccepted(awaitingStateChannelSnapshots: Set[StateChannelOutput])
      extends GlobalSnapshotValidationError
  case class InvalidSigned(error: SignedValidationError) extends GlobalSnapshotValidationError
}
