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

import org.typelevel.log4cats.slf4j.Slf4jLogger

/** Accepts or rejects delegated stake create/withdraw events for inclusion in a global snapshot.
  *
  * '''Determinism''': Uses `foldLeftM` with first-wins duplicate tracking (`parentRefsSeen`, `tokenLockRefsSeen`, `stakeRefsSeen`). The
  * input lists MUST be in canonical order — if two creates share the same parent, only the first in iteration order is accepted. Callers
  * must sort events before passing them to `accept()`.
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
    stakeRefsSeen: Set[Hash]
  )
  private object WithdrawDelegatedStakeAcceptanceResult {
    val empty = WithdrawDelegatedStakeAcceptanceResult(List.empty, List.empty, Set.empty)
  }

  def make[F[_]: Async: SecurityProvider](validator: UpdateDelegatedStakeValidator[F]) =
    new UpdateDelegatedStakeAcceptanceManager[F] {
      private val logger = Slf4jLogger.getLoggerFromClass[F](getClass)

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

          // Defensive sort: ensure deterministic first-wins duplicate resolution.
          // foldLeftM with parentRefsSeen/tokenLockRefsSeen is order-dependent.
          sortedCreates = creates.sortBy(_.show)
          sortedWithdrawals = withdrawals.sortBy(_.show)

          createResult <- sortedCreates.foldLeftM(CreateDelegatedStakeAcceptanceResult.empty) { (acc, signed) =>
            validator
              .validateCreateDelegatedStake(signed, lastSnapshotContext)
              .map(processCreateValidation(acc, signed, _, acceptedTokenLocks))
          }

          withdrawResult <- sortedWithdrawals.foldLeftM(WithdrawDelegatedStakeAcceptanceResult.empty) { (acc, signed) =>
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

          _ <- logger.debug(
            s"[DELEG_STAKE] ordinal=${currentSnapshotOrdinal.show} " +
              s"input: creates=${creates.size} withdrawals=${withdrawals.size} " +
              s"existing=${hashedExistingDelegatedStakes.size} acceptedTokenLocks=${acceptedTokenLocks.size} | " +
              s"result: acceptedCreates=${createResult.accepted.size} rejectedCreates=${createResult.rejected.size} " +
              s"acceptedWithdrawals=${withdrawResult.accepted.size} rejectedWithdrawals=${withdrawResult.rejected.size} " +
              s"duplicateParents=${createResult.parentRefsSeen.size} duplicateTokenLocks=${createResult.tokenLockRefsSeen.size} " +
              s"duplicateStakes=${withdrawResult.stakeRefsSeen.size}" +
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
