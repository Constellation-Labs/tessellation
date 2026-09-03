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
import io.constellationnetwork.schema.tokenLock.TokenLock
import io.constellationnetwork.schema.{GlobalSnapshotInfo, SnapshotOrdinal}
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.{Hasher, SecurityProvider}
import io.constellationnetwork.syntax.sortedCollection.sortedMapSyntax

import org.typelevel.log4cats.slf4j.Slf4jLogger

/** Accepts or rejects delegated stake create/withdraw events for inclusion in a global snapshot.
  *
  * '''Determinism''': Uses `foldLeftM` with first-wins duplicate tracking (`parentRefsSeen`, `tokenLockRefsSeen`, `stakeRefsSeen`). The
  * manager sorts signed envelopes itself. The hardened path uses the established total `Signed` ordering, including proofs; the legacy path
  * retains its historical Show-based ordering for replay compatibility.
  */
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
    stakeRefsSeen: Set[Hash],
    acceptedTokenLockRefs: Set[Hash]
  )
  private object WithdrawDelegatedStakeAcceptanceResult {
    val empty = WithdrawDelegatedStakeAcceptanceResult(List.empty, List.empty, Set.empty, Set.empty)
  }

  def make[F[_]: Async: SecurityProvider](
    validator: UpdateDelegatedStakeValidator[F],
    fixingDelegatedStakeDoubleWithdrawalOrdinal: SnapshotOrdinal = SnapshotOrdinal.MaxValue
  ) =
    new UpdateDelegatedStakeAcceptanceManager[F] {
      private val logger = Slf4jLogger.getLoggerFromClass[F](getClass)

      private def processCreateValidation(
        acc: CreateDelegatedStakeAcceptanceResult,
        signed: Signed[UpdateDelegatedStake.Create],
        validated: UpdateDelegatedStakeValidationErrorOr[Signed[UpdateDelegatedStake.Create]],
        acceptedTokenLocks: List[Signed[TokenLock]],
        isDoubleWithdrawalFixActive: Boolean
      ): CreateDelegatedStakeAcceptanceResult = {
        def reject(error: UpdateDelegatedStakeValidationError) =
          (acc.accepted, (signed, NonEmptyChain.of(error)) :: acc.rejected)

        val (newAccepted, newRejected, wasAccepted) = validated match {
          case Valid(_) if acc.parentRefsSeen(signed.parent) =>
            val (accepted, rejected) = reject(DuplicatedParent(signed.parent))
            (accepted, rejected, false)
          case Valid(_) if acc.tokenLockRefsSeen(signed.tokenLockRef) =>
            val (accepted, rejected) = reject(DuplicatedTokenLock(signed.tokenLockRef))
            (accepted, rejected, false)
          case Valid(_) if acceptedTokenLocks.exists(_.value.replaceTokenLockRef == signed.tokenLockRef.some) =>
            val (accepted, rejected) = reject(OutdatedTokenLock(signed.tokenLockRef))
            (accepted, rejected, false)
          case Valid(a) =>
            (a :: acc.accepted, acc.rejected, true)
          case Invalid(e) =>
            (acc.accepted, (signed, e) :: acc.rejected, false)
        }

        val newParentRefsSeen =
          if (!isDoubleWithdrawalFixActive || wasAccepted) acc.parentRefsSeen + signed.parent
          else acc.parentRefsSeen
        val newTokenLockRefsSeen =
          if (!isDoubleWithdrawalFixActive || wasAccepted) acc.tokenLockRefsSeen + signed.tokenLockRef
          else acc.tokenLockRefsSeen

        CreateDelegatedStakeAcceptanceResult(
          newAccepted,
          newRejected,
          newParentRefsSeen,
          newTokenLockRefsSeen
        )
      }

      private def processWithdrawValidation(
        acc: WithdrawDelegatedStakeAcceptanceResult,
        signed: Signed[UpdateDelegatedStake.Withdraw],
        validated: UpdateDelegatedStakeValidationErrorOr[Signed[UpdateDelegatedStake.Withdraw]],
        hashedExistingDelegatedStakes: Map[Hash, DelegatedStakeRecord],
        acceptedTokenLocks: List[Signed[TokenLock]],
        acceptedCreateTokenLockRefs: Set[Hash],
        pendingWithdrawalTokenLockRefs: Set[Hash],
        isDoubleWithdrawalFixActive: Boolean
      ): WithdrawDelegatedStakeAcceptanceResult = {
        def reject(error: UpdateDelegatedStakeValidationError) =
          (acc.accepted, (signed, NonEmptyChain.of(error)) :: acc.rejected)

        def hasOutdatedTokenLock(maybeRecord: Option[DelegatedStakeRecord]): Boolean =
          acceptedTokenLocks.exists { lock =>
            lock.value.replaceTokenLockRef.isDefined &&
            lock.value.replaceTokenLockRef == maybeRecord.map(_.tokenLockRef)
          }

        val maybeExistingDelegatedStake = hashedExistingDelegatedStakes.get(signed.stakeRef)
        val maybeEffectiveTokenLockRef = maybeExistingDelegatedStake.map(_.tokenLockRef)

        val (newAccepted, newRejected, wasAccepted) = validated match {
          case Valid(_) if acc.stakeRefsSeen(signed.stakeRef) =>
            val (accepted, rejected) = reject(DuplicatedStake(signed.stakeRef))
            (accepted, rejected, false)
          case Valid(_) if hasOutdatedTokenLock(maybeExistingDelegatedStake) =>
            val (accepted, rejected) = reject(OutdatedTokenLock(maybeEffectiveTokenLockRef.getOrElse(Hash.empty)))
            (accepted, rejected, false)
          case Valid(_) if isDoubleWithdrawalFixActive && maybeEffectiveTokenLockRef.isEmpty =>
            val (accepted, rejected) = reject(InvalidStake(signed.stakeRef))
            (accepted, rejected, false)
          case Valid(_) if isDoubleWithdrawalFixActive && maybeEffectiveTokenLockRef.exists(acceptedCreateTokenLockRefs) =>
            val (accepted, rejected) = reject(DuplicatedTokenLock(maybeEffectiveTokenLockRef.get))
            (accepted, rejected, false)
          case Valid(_) if isDoubleWithdrawalFixActive && maybeEffectiveTokenLockRef.exists(pendingWithdrawalTokenLockRefs) =>
            val (accepted, rejected) = reject(AlreadyWithdrawn(maybeEffectiveTokenLockRef.get))
            (accepted, rejected, false)
          case Valid(_) if isDoubleWithdrawalFixActive && maybeEffectiveTokenLockRef.exists(acc.acceptedTokenLockRefs) =>
            val (accepted, rejected) = reject(DuplicatedTokenLock(maybeEffectiveTokenLockRef.get))
            (accepted, rejected, false)
          case Valid(a) =>
            (a :: acc.accepted, acc.rejected, true)
          case Invalid(e) =>
            (acc.accepted, (signed, e) :: acc.rejected, false)
        }

        val newAcceptedTokenLockRefs =
          if (isDoubleWithdrawalFixActive && wasAccepted) acc.acceptedTokenLockRefs ++ maybeEffectiveTokenLockRef
          else acc.acceptedTokenLockRefs

        val newStakeRefsSeen =
          if (!isDoubleWithdrawalFixActive || wasAccepted) acc.stakeRefsSeen + signed.stakeRef
          else acc.stakeRefsSeen

        WithdrawDelegatedStakeAcceptanceResult(
          newAccepted,
          newRejected,
          newStakeRefsSeen,
          newAcceptedTokenLockRefs
        )
      }

      def accept(
        creates: List[Signed[UpdateDelegatedStake.Create]],
        withdrawals: List[Signed[UpdateDelegatedStake.Withdraw]],
        lastSnapshotContext: GlobalSnapshotInfo,
        currentGlobalEpochProgress: EpochProgress,
        currentSnapshotOrdinal: SnapshotOrdinal,
        acceptedTokenLocks: List[Signed[TokenLock]]
      )(implicit hasher: Hasher[F]): F[UpdateDelegatedStakeAcceptanceResult] = {
        val isDoubleWithdrawalFixActive = currentSnapshotOrdinal >= fixingDelegatedStakeDoubleWithdrawalOrdinal

        for {
          hashedExistingDelegatedStakes <- lastSnapshotContext.activeDelegatedStakes
            .getOrElse(SortedMap.empty[Address, SortedSet[DelegatedStakeRecord]])
            .values
            .flatten
            .toList
            .traverse(record => record.event.toHashed.map(_.hash -> record))
            .map(_.toMap)

          // Keep the legacy ordering byte-compatible below activation. Above it, use the established total Signed ordering so distinct
          // proof envelopes for the same value cannot inherit nondeterminism from input order.
          sortedCreates =
            if (isDoubleWithdrawalFixActive) creates.sorted(Signed.order[UpdateDelegatedStake.Create].toOrdering)
            else creates.sortBy(_.show)
          sortedWithdrawals =
            if (isDoubleWithdrawalFixActive) withdrawals.sorted(Signed.order[UpdateDelegatedStake.Withdraw].toOrdering)
            else withdrawals.sortBy(_.show)

          createResult <- sortedCreates.foldLeftM(CreateDelegatedStakeAcceptanceResult.empty) { (acc, signed) =>
            validator
              .validateCreateDelegatedStake(signed, lastSnapshotContext)
              .map(processCreateValidation(acc, signed, _, acceptedTokenLocks, isDoubleWithdrawalFixActive))
          }

          acceptedCreateTokenLockRefs =
            if (isDoubleWithdrawalFixActive) createResult.accepted.iterator.map(_.tokenLockRef).toSet
            else Set.empty[Hash]

          pendingWithdrawalTokenLockRefs =
            if (isDoubleWithdrawalFixActive)
              lastSnapshotContext.delegatedStakesWithdrawals
                .getOrElse(SortedMap.empty[Address, SortedSet[PendingDelegatedStakeWithdrawal]])
                .valuesIterator
                .flatMap(_.iterator.map(_.tokenLockRef))
                .toSet
            else Set.empty[Hash]

          withdrawResult <- sortedWithdrawals.foldLeftM(WithdrawDelegatedStakeAcceptanceResult.empty) { (acc, signed) =>
            validator
              .validateWithdrawDelegatedStake(signed, lastSnapshotContext)
              .map(
                processWithdrawValidation(
                  acc,
                  signed,
                  _,
                  hashedExistingDelegatedStakes,
                  acceptedTokenLocks,
                  acceptedCreateTokenLockRefs,
                  pendingWithdrawalTokenLockRefs,
                  isDoubleWithdrawalFixActive
                )
              )
          }

          acceptedCreatesMap <- createResult.accepted
            .map(c => (c, currentSnapshotOrdinal))
            .traverse { case (signed, ord) => signed.proofs.head.id.toAddress.map((_, (signed, ord))) }
            .map(_.groupBy(_._1).view.mapValues(_.map(_._2)).toSortedMap)

          acceptedWithdrawalsMap <- withdrawResult.accepted
            .map(w => (w, currentGlobalEpochProgress))
            .traverse { case (signed, epoch) => signed.proofs.head.id.toAddress.map((_, (signed, epoch))) }
            .map(_.groupBy(_._1).view.mapValues(_.map(_._2)).toSortedMap)

          _ <- logger.debug(
            s"[DELEG_STAKE] ordinal=${currentSnapshotOrdinal.show} " +
              s"input: creates=${creates.size} withdrawals=${withdrawals.size} " +
              s"existing=${hashedExistingDelegatedStakes.size} acceptedTokenLocks=${acceptedTokenLocks.size} | " +
              s"result: acceptedCreates=${createResult.accepted.size} rejectedCreates=${createResult.rejected.size} " +
              s"acceptedWithdrawals=${withdrawResult.accepted.size} rejectedWithdrawals=${withdrawResult.rejected.size} " +
              s"duplicateParents=${createResult.parentRefsSeen.size} duplicateTokenLocks=${createResult.tokenLockRefsSeen.size} " +
              s"duplicateStakes=${withdrawResult.stakeRefsSeen.size} acceptedWithdrawalTokenLocks=${withdrawResult.acceptedTokenLockRefs.size}" +
              (if (createResult.rejected.nonEmpty)
                 s" rejectReasons=[${createResult.rejected.map(_._2.head.getClass.getSimpleName).distinct.mkString(",")}]"
               else "") +
              (if (withdrawResult.rejected.nonEmpty)
                 s" withdrawRejectReasons=[${withdrawResult.rejected.map(_._2.head.getClass.getSimpleName).distinct.mkString(",")}]"
               else "")
          )
        } yield
          UpdateDelegatedStakeAcceptanceResult(
            acceptedCreatesMap,
            createResult.rejected,
            acceptedWithdrawalsMap,
            withdrawResult.rejected
          )
      }
    }
}
