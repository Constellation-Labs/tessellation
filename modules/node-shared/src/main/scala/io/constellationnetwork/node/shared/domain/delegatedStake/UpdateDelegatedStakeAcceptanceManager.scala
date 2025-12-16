package io.constellationnetwork.node.shared.domain.delegatedStake

import cats.data.NonEmptyChain
import cats.data.Validated.{Invalid, Valid}
import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.node.shared.domain.delegatedStake.UpdateDelegatedStakeValidator._
import io.constellationnetwork.schema.delegatedStake.{DelegatedStakeReference, UpdateDelegatedStake}
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.{GlobalSnapshotInfo, SnapshotOrdinal}
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.syntax.sortedCollection.sortedMapSyntax

trait UpdateDelegatedStakeAcceptanceManager[F[_]] {

  def accept(
    creates: List[Signed[UpdateDelegatedStake.Create]],
    withdrawals: List[Signed[UpdateDelegatedStake.Withdraw]],
    lastSnapshotContext: GlobalSnapshotInfo,
    currentGlobalEpochProgress: EpochProgress,
    currentSnapshotOrdinal: SnapshotOrdinal
  ): F[UpdateDelegatedStakeAcceptanceResult]

}

object UpdateDelegatedStakeAcceptanceManager {

  private case class CreateDelegatedStakeAcceptanceResult(
    accepted: List[Signed[UpdateDelegatedStake.Create]],
    rejected: List[(Signed[UpdateDelegatedStake.Create], NonEmptyChain[UpdateDelegatedStakeValidationError])],
    parentRefsSeen: Set[DelegatedStakeReference],
    tokenLockRefsSeen: Set[Hash]
  )
  private object CreateDelegatedStakeAcceptanceResult {
    val empty = CreateDelegatedStakeAcceptanceResult(List.empty, List.empty, Set.empty, Set.empty)
  }

  private case class WithdrawDelegatedStakeAcceptanceResult(
    accepted: List[Signed[UpdateDelegatedStake.Withdraw]],
    rejected: List[(Signed[UpdateDelegatedStake.Withdraw], NonEmptyChain[UpdateDelegatedStakeValidationError])],
    stakeRefsSeen: Set[Hash]
  )
  private object WithdrawDelegatedStakeAcceptanceResult {
    val empty = WithdrawDelegatedStakeAcceptanceResult(List.empty, List.empty, Set.empty)
  }

  def make[F[_]: Async: SecurityProvider](validator: UpdateDelegatedStakeValidator[F]) =
    new UpdateDelegatedStakeAcceptanceManager[F] {
      def accept(
        creates: List[Signed[UpdateDelegatedStake.Create]],
        withdrawals: List[Signed[UpdateDelegatedStake.Withdraw]],
        lastSnapshotContext: GlobalSnapshotInfo,
        currentGlobalEpochProgress: EpochProgress,
        currentSnapshotOrdinal: SnapshotOrdinal
      ): F[UpdateDelegatedStakeAcceptanceResult] =
        for {
          createDelegatedStakeAcceptanceResult <- creates.foldLeftM[F, CreateDelegatedStakeAcceptanceResult](
            CreateDelegatedStakeAcceptanceResult.empty
          ) { (acc, signed) =>
            validator.validateCreateDelegatedStake(signed, lastSnapshotContext).map { validated =>
              val (newAccepted, newRejected) = validated match {
                case Valid(a) =>
                  if (acc.parentRefsSeen(signed.parent)) {
                    (acc.accepted, (signed, NonEmptyChain.of(DuplicatedParent(signed.parent))) :: acc.rejected)
                  } else if (acc.tokenLockRefsSeen(signed.tokenLockRef)) {
                    (acc.accepted, (signed, NonEmptyChain.of(DuplicatedTokenLock(signed.tokenLockRef))) :: acc.rejected)
                  } else {
                    (a :: acc.accepted, acc.rejected)
                  }
                case Invalid(e) => (acc.accepted, (signed, e) :: acc.rejected)
              }

              val newParentRefsSeen = acc.parentRefsSeen + signed.parent
              val newTokenLockRefsSeen = acc.tokenLockRefsSeen + signed.tokenLockRef
              CreateDelegatedStakeAcceptanceResult(newAccepted, newRejected, newParentRefsSeen, newTokenLockRefsSeen)
            }
          }
          withdrawDelegatedStakeAcceptanceResult <- withdrawals.foldLeftM[F, WithdrawDelegatedStakeAcceptanceResult](
            WithdrawDelegatedStakeAcceptanceResult.empty
          ) { (acc, signed) =>
            validator.validateWithdrawDelegatedStake(signed, lastSnapshotContext).map { validated =>
              val (newAccepted, newRejected) = validated match {
                case Valid(a) =>
                  if (acc.stakeRefsSeen(signed.stakeRef)) {
                    (acc.accepted, (signed, NonEmptyChain.of(DuplicatedStake(signed.stakeRef))) :: acc.rejected)
                  } else {
                    (a :: acc.accepted, acc.rejected)
                  }
                case Invalid(e) => (acc.accepted, (signed, e) :: acc.rejected)
              }
              val newStakeRefsSeen = acc.stakeRefsSeen + signed.stakeRef
              WithdrawDelegatedStakeAcceptanceResult(newAccepted, newRejected, newStakeRefsSeen)
            }
          }

          acceptedCreatesMap <- createDelegatedStakeAcceptanceResult.accepted
            .map(c => (c, currentSnapshotOrdinal))
            .traverse { case (signed, ord) => signed.proofs.head.id.toAddress.map((_, (signed, ord))) }
            .map(_.groupBy(_._1).view.mapValues(_.map(_._2)).toSortedMap)

          acceptedWithdrawalsMap <- withdrawDelegatedStakeAcceptanceResult.accepted
            .map(w => (w, currentGlobalEpochProgress))
            .traverse { case (signed, epoch) => signed.proofs.head.id.toAddress.map((_, (signed, epoch))) }
            .map(_.groupBy(_._1).view.mapValues(_.map(_._2)).toSortedMap)

        } yield
          UpdateDelegatedStakeAcceptanceResult(
            acceptedCreatesMap,
            createDelegatedStakeAcceptanceResult.rejected,
            acceptedWithdrawalsMap,
            withdrawDelegatedStakeAcceptanceResult.rejected
          )
    }
}
