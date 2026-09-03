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
    tokenLockRefsSeen: Set[(Address, Hash)]
  )
  private object WithdrawDelegatedStakeAcceptanceResult {
    val empty = WithdrawDelegatedStakeAcceptanceResult(List.empty, List.empty, Set.empty, Set.empty)
  }

  def make[F[_]: Async: SecurityProvider](
    validator: UpdateDelegatedStakeValidator[F],
    fixingDelegatedStakeDoubleWithdrawalOrdinal: SnapshotOrdinal = SnapshotOrdinal.unsafeApply(Long.MaxValue)
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
        implicit val createOrdering: Ordering[UpdateDelegatedStake.Create] =
          cats.Order[UpdateDelegatedStake.Create].toOrdering
        implicit val withdrawalOrdering: Ordering[UpdateDelegatedStake.Withdraw] =
          Ordering.by(withdrawal => (withdrawal.source, withdrawal.stakeRef))
        val createsForAcceptance =
          if (isDoubleWithdrawalFixActive) creates.sorted else creates
        val withdrawalsForAcceptance =
          if (isDoubleWithdrawalFixActive) withdrawals.sorted else withdrawals

        for {
          createDelegatedStakeAcceptanceResult <- createsForAcceptance.foldLeftM[F, CreateDelegatedStakeAcceptanceResult](
            CreateDelegatedStakeAcceptanceResult.empty
          ) { (acc, signed) =>
            validator.validateCreateDelegatedStake(signed, lastSnapshotContext).map { validated =>
              val (newAccepted, newRejected, accepted) = validated match {
                case Valid(a) =>
                  if (acc.parentRefsSeen(signed.parent)) {
                    (acc.accepted, (signed, NonEmptyChain.of(DuplicatedParent(signed.parent))) :: acc.rejected, false)
                  } else if (acc.tokenLockRefsSeen(signed.tokenLockRef)) {
                    (acc.accepted, (signed, NonEmptyChain.of(DuplicatedTokenLock(signed.tokenLockRef))) :: acc.rejected, false)
                  } else {
                    (a :: acc.accepted, acc.rejected, true)
                  }
                case Invalid(e) => (acc.accepted, (signed, e) :: acc.rejected, false)
              }

              val newParentRefsSeen =
                if (!isDoubleWithdrawalFixActive || accepted) acc.parentRefsSeen + signed.parent else acc.parentRefsSeen
              val newTokenLockRefsSeen =
                if (!isDoubleWithdrawalFixActive || accepted) acc.tokenLockRefsSeen + signed.tokenLockRef else acc.tokenLockRefsSeen
              CreateDelegatedStakeAcceptanceResult(newAccepted, newRejected, newParentRefsSeen, newTokenLockRefsSeen)
            }
          }
          // A create that replaces an active stake wins deterministically over a withdrawal of that
          // same stake in this snapshot. Before activation these sets stay empty to preserve history.
          acceptedCreateTokenLockRefs =
            if (isDoubleWithdrawalFixActive)
              createDelegatedStakeAcceptanceResult.accepted.iterator.map(c => (c.source, c.tokenLockRef)).toSet
            else Set.empty[(Address, Hash)]
          pendingWithdrawalTokenLockRefs =
            if (isDoubleWithdrawalFixActive)
              lastSnapshotContext.delegatedStakesWithdrawals
                .getOrElse(SortedMap.empty[Address, SortedSet[PendingDelegatedStakeWithdrawal]])
                .valuesIterator
                .flatMap(_.iterator.map(w => (w.event.source, w.event.tokenLockRef)))
                .toSet
            else Set.empty[(Address, Hash)]
          stakeTokenLockRefs <-
            if (isDoubleWithdrawalFixActive) {
              val withdrawalSources = withdrawalsForAcceptance.iterator.map(_.source).toSet
              lastSnapshotContext.activeDelegatedStakes
                .getOrElse(SortedMap.empty[Address, SortedSet[DelegatedStakeRecord]])
                .iterator
                .filter { case (address, _) => withdrawalSources(address) }
                .flatMap { case (address, records) => records.iterator.map(address -> _) }
                .toList
                .traverse {
                  case (address, record) =>
                    DelegatedStakeReference.of(record.event).map(ref => (address, ref.hash) -> record.event.tokenLockRef)
                }
                .map(_.toMap)
            } else Map.empty[(Address, Hash), Hash].pure[F]
          withdrawDelegatedStakeAcceptanceResult <- withdrawalsForAcceptance.foldLeftM[F, WithdrawDelegatedStakeAcceptanceResult](
            WithdrawDelegatedStakeAcceptanceResult.empty
          ) { (acc, signed) =>
            validator.validateWithdrawDelegatedStake(signed, lastSnapshotContext).map { validated =>
              val maybeTokenLockRef = stakeTokenLockRefs.get((signed.source, signed.stakeRef))
              val maybeOwnedTokenLockRef = maybeTokenLockRef.map(signed.source -> _)
              val (newAccepted, newRejected, acceptedTokenLockRef) = validated match {
                case Valid(a) =>
                  if (!isDoubleWithdrawalFixActive) {
                    if (acc.stakeRefsSeen(signed.stakeRef))
                      (acc.accepted, (signed, NonEmptyChain.of(DuplicatedStake(signed.stakeRef))) :: acc.rejected, none)
                    else
                      (a :: acc.accepted, acc.rejected, none)
                  } else
                    maybeOwnedTokenLockRef match {
                      case None =>
                        (acc.accepted, (signed, NonEmptyChain.of(InvalidStake(signed.stakeRef))) :: acc.rejected, none)
                      case Some(ownedTokenLockRef) if acceptedCreateTokenLockRefs(ownedTokenLockRef) =>
                        (acc.accepted, (signed, NonEmptyChain.of(AlreadyWithdrawn(signed.stakeRef))) :: acc.rejected, none)
                      case Some(ownedTokenLockRef) if pendingWithdrawalTokenLockRefs(ownedTokenLockRef) =>
                        (acc.accepted, (signed, NonEmptyChain.of(AlreadyWithdrawn(ownedTokenLockRef._2))) :: acc.rejected, none)
                      case _ if acc.stakeRefsSeen(signed.stakeRef) =>
                        (acc.accepted, (signed, NonEmptyChain.of(DuplicatedStake(signed.stakeRef))) :: acc.rejected, none)
                      case Some(ownedTokenLockRef) if acc.tokenLockRefsSeen(ownedTokenLockRef) =>
                        (
                          acc.accepted,
                          (signed, NonEmptyChain.of(DuplicatedTokenLock(ownedTokenLockRef._2))) :: acc.rejected,
                          none
                        )
                      case _ =>
                        (a :: acc.accepted, acc.rejected, maybeOwnedTokenLockRef)
                    }
                case Invalid(e) => (acc.accepted, (signed, e) :: acc.rejected, none)
              }
              val newStakeRefsSeen =
                if (!isDoubleWithdrawalFixActive || acceptedTokenLockRef.nonEmpty) acc.stakeRefsSeen + signed.stakeRef
                else acc.stakeRefsSeen
              val newTokenLockRefsSeen = acc.tokenLockRefsSeen ++ acceptedTokenLockRef
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
