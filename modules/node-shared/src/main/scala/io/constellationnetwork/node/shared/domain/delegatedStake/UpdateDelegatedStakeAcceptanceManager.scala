package io.constellationnetwork.node.shared.domain.delegatedStake

import cats.data.NonEmptyChain
import cats.data.Validated.{Invalid, Valid}
import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.node.shared.domain.delegatedStake.UpdateDelegatedStakeValidator._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.delegatedStake.{DelegatedStakeRecord, DelegatedStakeReference, UpdateDelegatedStake}
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.tokenLock.TokenLock
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
    currentSnapshotOrdinal: SnapshotOrdinal,
    acceptedTokenLocks: List[Signed[TokenLock]]
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
    stakeRefsSeen: Set[Hash]
  )
  private object WithdrawDelegatedStakeAcceptanceResult {
    val empty = WithdrawDelegatedStakeAcceptanceResult(List.empty, List.empty, Set.empty)
  }

  def make[F[_]: Async: SecurityProvider](validator: UpdateDelegatedStakeValidator[F]) =
    new UpdateDelegatedStakeAcceptanceManager[F] {

      private def processCreateValidation(
        acc: CreateDelegatedStakeAcceptanceResult,
        signed: Signed[UpdateDelegatedStake.Create],
        validated: UpdateDelegatedStakeValidationErrorOr[Signed[UpdateDelegatedStake.Create]],
        acceptedTokenLocks: List[Signed[TokenLock]]
      ): CreateDelegatedStakeAcceptanceResult = {
        def reject(error: UpdateDelegatedStakeValidationError) =
          (acc.accepted, (signed, NonEmptyChain.of(error)) :: acc.rejected)

        val (newAccepted, newRejected) = validated match {
          case Valid(_) if acc.parentRefsSeen(signed.parent) =>
            reject(DuplicatedParent(signed.parent))
          case Valid(_) if acc.tokenLockRefsSeen(signed.tokenLockRef) =>
            reject(DuplicatedTokenLock(signed.tokenLockRef))
          case Valid(_) if acceptedTokenLocks.exists(_.value.replaceTokenLockRef == signed.tokenLockRef.some) =>
            reject(OutdatedTokenLock(signed.tokenLockRef))
          case Valid(a) =>
            (a :: acc.accepted, acc.rejected)
          case Invalid(e) =>
            (acc.accepted, (signed, e) :: acc.rejected)
        }

        CreateDelegatedStakeAcceptanceResult(
          newAccepted,
          newRejected,
          acc.parentRefsSeen + signed.parent,
          acc.tokenLockRefsSeen + signed.tokenLockRef
        )
      }

      private def processWithdrawValidation(
        acc: WithdrawDelegatedStakeAcceptanceResult,
        signed: Signed[UpdateDelegatedStake.Withdraw],
        validated: UpdateDelegatedStakeValidationErrorOr[Signed[UpdateDelegatedStake.Withdraw]],
        hashedExistingDelegatedStakes: Map[Hash, DelegatedStakeRecord],
        acceptedTokenLocks: List[Signed[TokenLock]]
      ): WithdrawDelegatedStakeAcceptanceResult = {
        def reject(error: UpdateDelegatedStakeValidationError) =
          (acc.accepted, (signed, NonEmptyChain.of(error)) :: acc.rejected)

        def hasOutdatedTokenLock(maybeRecord: Option[DelegatedStakeRecord]): Boolean =
          acceptedTokenLocks.exists { lock =>
            lock.value.replaceTokenLockRef.isDefined &&
            lock.value.replaceTokenLockRef == maybeRecord.map(_.tokenLockRef)
          }

        val maybeExistingDelegatedStake = hashedExistingDelegatedStakes.get(signed.stakeRef)

        val (newAccepted, newRejected) = validated match {
          case Valid(_) if acc.stakeRefsSeen(signed.stakeRef) =>
            reject(DuplicatedStake(signed.stakeRef))
          case Valid(_) if hasOutdatedTokenLock(maybeExistingDelegatedStake) =>
            reject(OutdatedTokenLock(maybeExistingDelegatedStake.map(_.tokenLockRef).getOrElse(Hash.empty)))
          case Valid(a) =>
            (a :: acc.accepted, acc.rejected)
          case Invalid(e) =>
            (acc.accepted, (signed, e) :: acc.rejected)
        }

        WithdrawDelegatedStakeAcceptanceResult(newAccepted, newRejected, acc.stakeRefsSeen + signed.stakeRef)
      }

      def accept(
        creates: List[Signed[UpdateDelegatedStake.Create]],
        withdrawals: List[Signed[UpdateDelegatedStake.Withdraw]],
        lastSnapshotContext: GlobalSnapshotInfo,
        currentGlobalEpochProgress: EpochProgress,
        currentSnapshotOrdinal: SnapshotOrdinal,
        acceptedTokenLocks: List[Signed[TokenLock]]
      )(implicit hasher: Hasher[F]): F[UpdateDelegatedStakeAcceptanceResult] =
        for {
          hashedExistingDelegatedStakes <- lastSnapshotContext.activeDelegatedStakes
            .getOrElse(SortedMap.empty[Address, SortedSet[DelegatedStakeRecord]])
            .values
            .flatten
            .toList
            .traverse(record => record.event.toHashed.map(_.hash -> record))
            .map(_.toMap)

          createResult <- creates.foldLeftM(CreateDelegatedStakeAcceptanceResult.empty) { (acc, signed) =>
            validator
              .validateCreateDelegatedStake(signed, lastSnapshotContext)
              .map(processCreateValidation(acc, signed, _, acceptedTokenLocks))
          }

          withdrawResult <- withdrawals.foldLeftM(WithdrawDelegatedStakeAcceptanceResult.empty) { (acc, signed) =>
            validator
              .validateWithdrawDelegatedStake(signed, lastSnapshotContext)
              .map(processWithdrawValidation(acc, signed, _, hashedExistingDelegatedStakes, acceptedTokenLocks))
          }

          acceptedCreatesMap <- createResult.accepted
            .map(c => (c, currentSnapshotOrdinal))
            .traverse { case (signed, ord) => signed.proofs.head.id.toAddress.map((_, (signed, ord))) }
            .map(_.groupBy(_._1).view.mapValues(_.map(_._2)).toSortedMap)

          acceptedWithdrawalsMap <- withdrawResult.accepted
            .map(w => (w, currentGlobalEpochProgress))
            .traverse { case (signed, epoch) => signed.proofs.head.id.toAddress.map((_, (signed, epoch))) }
            .map(_.groupBy(_._1).view.mapValues(_.map(_._2)).toSortedMap)

        } yield
          UpdateDelegatedStakeAcceptanceResult(
            acceptedCreatesMap,
            createResult.rejected,
            acceptedWithdrawalsMap,
            withdrawResult.rejected
          )
    }
}
