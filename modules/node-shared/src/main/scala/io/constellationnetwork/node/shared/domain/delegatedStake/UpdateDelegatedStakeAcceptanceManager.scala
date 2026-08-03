package io.constellationnetwork.node.shared.domain.delegatedStake

import cats.data.NonEmptyChain
import cats.data.Validated.{Invalid, Valid}
import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.node.shared.domain.delegatedStake.UpdateDelegatedStakeValidator._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.delegatedStake._
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.{GlobalSnapshotInfo, SnapshotOrdinal}
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.{Hasher, SecurityProvider}
import io.constellationnetwork.syntax.sortedCollection.sortedMapSyntax

trait UpdateDelegatedStakeAcceptanceManager[F[_]] {

  def accept(
    creates: List[Signed[UpdateDelegatedStake.Create]],
    withdrawals: List[Signed[UpdateDelegatedStake.Withdraw]],
    lastSnapshotContext: GlobalSnapshotInfo,
    currentGlobalEpochProgress: EpochProgress,
    currentSnapshotOrdinal: SnapshotOrdinal
  )(implicit hasher: Hasher[F]): F[UpdateDelegatedStakeAcceptanceResult]

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
    stakeRefsSeen: Set[Hash],
    tokenLockRefsSeen: Set[Hash]
  )
  private object WithdrawDelegatedStakeAcceptanceResult {
    val empty = WithdrawDelegatedStakeAcceptanceResult(List.empty, List.empty, Set.empty, Set.empty)
  }

  def make[F[_]: Async: SecurityProvider](
    validator: UpdateDelegatedStakeValidator[F],
    fixingDelegatedStakeDoubleWithdrawalOrdinal: SnapshotOrdinal
  ) =
    new UpdateDelegatedStakeAcceptanceManager[F] {
      def accept(
        creates: List[Signed[UpdateDelegatedStake.Create]],
        withdrawals: List[Signed[UpdateDelegatedStake.Withdraw]],
        lastSnapshotContext: GlobalSnapshotInfo,
        currentGlobalEpochProgress: EpochProgress,
        currentSnapshotOrdinal: SnapshotOrdinal
      )(implicit hasher: Hasher[F]): F[UpdateDelegatedStakeAcceptanceResult] = {
        val isDoubleWithdrawalFixActive = currentSnapshotOrdinal >= fixingDelegatedStakeDoubleWithdrawalOrdinal

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
          // At and after the activation ordinal, creates win deterministically when a replacement
          // and withdrawal for the same lock are proposed together.
          acceptedCreateTokenLockRefs =
            if (isDoubleWithdrawalFixActive)
              createDelegatedStakeAcceptanceResult.accepted.map(c => (c.source, c.tokenLockRef)).toSet
            else Set.empty[(Address, Hash)]
          pendingWithdrawalTokenLockRefs =
            if (isDoubleWithdrawalFixActive)
              lastSnapshotContext.delegatedStakesWithdrawals
                .getOrElse(SortedMap.empty[Address, SortedSet[PendingDelegatedStakeWithdrawal]])
                .iterator
                .flatMap { case (address, pending) => pending.iterator.map(w => (address, w.event.tokenLockRef)) }
                .toSet
            else Set.empty[(Address, Hash)]
          existingStakeTokenLockRefs <-
            if (isDoubleWithdrawalFixActive) {
              val withdrawalSources = withdrawals.iterator.map(_.source).toSet
              lastSnapshotContext.activeDelegatedStakes
                .getOrElse(SortedMap.empty[Address, SortedSet[DelegatedStakeRecord]])
                .toList
                .filter { case (address, _) => withdrawalSources(address) }
                .flatMap { case (address, records) => records.toList.map(address -> _) }
                .traverse {
                  case (address, record) =>
                    DelegatedStakeReference.of(record.event).map(ref => (address, ref.hash) -> record.event.tokenLockRef)
                }
                .map(_.toMap)
            } else Map.empty[(Address, Hash), Hash].pure[F]
          withdrawDelegatedStakeAcceptanceResult <- withdrawals.foldLeftM[F, WithdrawDelegatedStakeAcceptanceResult](
            WithdrawDelegatedStakeAcceptanceResult.empty
          ) { (acc, signed) =>
            validator.validateWithdrawDelegatedStake(signed, lastSnapshotContext).map { validated =>
              val maybeTokenLockRef = existingStakeTokenLockRefs.get((signed.source, signed.stakeRef))
              val (newAccepted, newRejected) = validated match {
                case Valid(a) =>
                  if (!isDoubleWithdrawalFixActive) {
                    if (acc.stakeRefsSeen(signed.stakeRef))
                      (acc.accepted, (signed, NonEmptyChain.of(DuplicatedStake(signed.stakeRef))) :: acc.rejected)
                    else
                      (a :: acc.accepted, acc.rejected)
                  } else
                    maybeTokenLockRef match {
                      case Some(tokenLockRef) if acceptedCreateTokenLockRefs((signed.source, tokenLockRef)) =>
                        (
                          acc.accepted,
                          (signed, NonEmptyChain.of(AlreadyWithdrawn(signed.stakeRef))) :: acc.rejected
                        )
                      case Some(tokenLockRef) if pendingWithdrawalTokenLockRefs((signed.source, tokenLockRef)) =>
                        (
                          acc.accepted,
                          (signed, NonEmptyChain.of(AlreadyWithdrawn(tokenLockRef))) :: acc.rejected
                        )
                      case _ if acc.stakeRefsSeen(signed.stakeRef) =>
                        (acc.accepted, (signed, NonEmptyChain.of(DuplicatedStake(signed.stakeRef))) :: acc.rejected)
                      case Some(tokenLockRef) if acc.tokenLockRefsSeen(tokenLockRef) =>
                        (
                          acc.accepted,
                          (signed, NonEmptyChain.of(DuplicatedTokenLock(tokenLockRef))) :: acc.rejected
                        )
                      case _ =>
                        (a :: acc.accepted, acc.rejected)
                    }
                case Invalid(e) => (acc.accepted, (signed, e) :: acc.rejected)
              }
              val newStakeRefsSeen = acc.stakeRefsSeen + signed.stakeRef
              val newTokenLockRefsSeen =
                if (isDoubleWithdrawalFixActive) acc.tokenLockRefsSeen ++ maybeTokenLockRef else acc.tokenLockRefsSeen
              WithdrawDelegatedStakeAcceptanceResult(newAccepted, newRejected, newStakeRefsSeen, newTokenLockRefsSeen)
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
}
