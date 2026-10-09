package io.constellationnetwork.node.shared.infrastructure.snapshot.managers.global

import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.schema._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.delegatedStake._
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.tokenLock.TokenLock
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.syntax.sortedCollection.sortedMapSyntax

trait DelegatedStakeStateManager[F[_]] {
  def acceptDelegatedStakes1(
    lastSnapshotContext: GlobalSnapshotInfo,
    epochProgress: EpochProgress,
    withdrawalTimeLimit: EpochProgress
  ): (
    SortedMap[Address, SortedSet[DelegatedStakeRecord]],
    SortedMap[Address, SortedSet[PendingDelegatedStakeWithdrawal]],
    SortedMap[Address, SortedSet[PendingDelegatedStakeWithdrawal]]
  )

  def processExistingDelegatedStakes(
    lastSnapshotContext: GlobalSnapshotInfo,
    epochProgress: EpochProgress,
    acceptedTokenLocks: List[Signed[TokenLock]],
    withdrawalTimeLimit: EpochProgress
  )(implicit hasher: Hasher[F]): F[PartitionedRecords[SortedSet[DelegatedStakeRecord], SortedSet[PendingDelegatedStakeWithdrawal]]]
}

object DelegatedStakeStateManager {

  def make[F[_]: Async](): DelegatedStakeStateManager[F] = new DelegatedStakeStateManager[F] {

    override def processExistingDelegatedStakes(
      lastSnapshotContext: GlobalSnapshotInfo,
      epochProgress: EpochProgress,
      acceptedTokenLocks: List[Signed[TokenLock]],
      withdrawalTimeLimit: EpochProgress
    )(implicit hasher: Hasher[F]): F[PartitionedRecords[SortedSet[DelegatedStakeRecord], SortedSet[PendingDelegatedStakeWithdrawal]]] = {
      def isWithdrawalExpired(withdrawalEpoch: EpochProgress): Boolean =
        (withdrawalEpoch |+| withdrawalTimeLimit) <= epochProgress

      val existingDelegatedStakes = lastSnapshotContext.activeDelegatedStakes.getOrElse(
        SortedMap.empty[Address, SortedSet[DelegatedStakeRecord]]
      )

      val existingWithdrawals = lastSnapshotContext.delegatedStakesWithdrawals.getOrElse(
        SortedMap.empty[Address, SortedSet[PendingDelegatedStakeWithdrawal]]
      )

      for {
        hashedReplacementTokenLocks <- acceptedTokenLocks.filter(_.replaceTokenLockRef.isDefined).traverse(_.toHashed)
        replacementTokenLocks = hashedReplacementTokenLocks.mapFilter(tl => tl.replaceTokenLockRef.tupleRight(tl)).toMap

        // Build a map of active token locks by reference for checking if token locks are still active
        activeTokenLocksByRef <- lastSnapshotContext.activeTokenLocks
          .getOrElse(SortedMap.empty[Address, SortedSet[Signed[TokenLock]]])
          .values
          .toList
          .flatten
          .traverse(_.toHashed)
          .map(_.map(hashed => hashed.hash -> hashed).toMap)

        updatedExistingDelegatedStakes = existingDelegatedStakes.view
          .mapValues(_.map { record =>
            replacementTokenLocks.get(record.tokenLockRef).fold(record) { hashedTokenLock =>
              record.copy(
                currentTokenLockRef = hashedTokenLock.hash.some,
                currentAmount = DelegatedStakeAmount.fromTokenLockAmount(hashedTokenLock.amount).some
              )
            }
          })
          .toSortedMap

        updatedExistingWithdrawals = existingWithdrawals.view
          .mapValues(_.map { record =>
            replacementTokenLocks
              .get(record.tokenLockRef)
              .filter(_ => activeTokenLocksByRef.contains(record.tokenLockRef))
              .fold(record) { hashedTokenLock =>
                record.copy(
                  currentTokenLockRef = hashedTokenLock.hash.some,
                  currentAmount = DelegatedStakeAmount.fromTokenLockAmount(hashedTokenLock.amount).some
                )
              }
          })
          .toSortedMap

        unexpiredWithdrawals = updatedExistingWithdrawals.map {
          case (address, withdrawals) =>
            address -> withdrawals.filterNot {
              case PendingDelegatedStakeWithdrawal(_, _, _, withdrawalEpoch, _, _) =>
                isWithdrawalExpired(withdrawalEpoch)
            }
        }.filter { case (_, withdrawalList) => withdrawalList.nonEmpty }

        expiredWithdrawals = updatedExistingWithdrawals.map {
          case (address, withdrawals) =>
            address -> withdrawals.filter {
              case PendingDelegatedStakeWithdrawal(_, _, _, withdrawalEpoch, _, _) =>
                isWithdrawalExpired(withdrawalEpoch)
            }
        }.filter { case (_, withdrawalList) => withdrawalList.nonEmpty }

        // A due withdrawal whose lock is replaced in this snapshot is rewritten to the NEW reference above. NEW only becomes
        // last-active at R+1, so the withdrawal is carried in pending for one round and settles there.
        replacementRefs = replacementTokenLocks.values.map(_.hash).toSet
        isCarriedByReplacement = (withdrawal: PendingDelegatedStakeWithdrawal) =>
          !activeTokenLocksByRef.contains(withdrawal.tokenLockRef) && replacementRefs.contains(withdrawal.tokenLockRef)

        // Every other expired withdrawal stays in `expired` even when its token lock is gone (an orphan), exactly as on
        // release/mainnet. Below removing-processed-delegated-stake-withdrawals (#1498) unlock generation then fails with
        // "Token lock not found", as v3.5 did; at/after it unlock generation skips the orphan, its recorded rewards are paid on
        // the legacy path and acceptance removes its reference from pending (see GlobalSnapshotAcceptanceManager).
        keepPending = isCarriedByReplacement

        finalUnexpiredWithdrawals = unexpiredWithdrawals |+| expiredWithdrawals.map {
          case (address, withdrawals) => address -> withdrawals.filter(keepPending)
        }.filter { case (_, withdrawalList) => withdrawalList.nonEmpty }

        finalExpiredWithdrawals = expiredWithdrawals.map {
          case (address, withdrawals) => address -> withdrawals.filterNot(keepPending)
        }.filter { case (_, withdrawalList) => withdrawalList.nonEmpty }

      } yield
        PartitionedRecords(
          updatedExistingDelegatedStakes,
          finalUnexpiredWithdrawals,
          finalExpiredWithdrawals
        )
    }

    def acceptDelegatedStakes1(
      lastSnapshotContext: GlobalSnapshotInfo,
      epochProgress: EpochProgress,
      withdrawalTimeLimit: EpochProgress
    ): (
      SortedMap[Address, SortedSet[DelegatedStakeRecord]],
      SortedMap[Address, SortedSet[PendingDelegatedStakeWithdrawal]],
      SortedMap[Address, SortedSet[PendingDelegatedStakeWithdrawal]]
    ) = {
      val existingDelegatedStakes = lastSnapshotContext.activeDelegatedStakes.getOrElse(
        SortedMap.empty[Address, SortedSet[DelegatedStakeRecord]]
      )

      val existingWithdrawals = lastSnapshotContext.delegatedStakesWithdrawals.getOrElse(
        SortedMap.empty[Address, SortedSet[PendingDelegatedStakeWithdrawal]]
      )

      def isWithdrawalExpired(withdrawalEpoch: EpochProgress): Boolean =
        (withdrawalEpoch |+| withdrawalTimeLimit) <= epochProgress

      val unexpiredWithdrawals = existingWithdrawals.map {
        case (address, withdrawals) =>
          address -> withdrawals.filterNot {
            case PendingDelegatedStakeWithdrawal(_, _, _, withdrawalEpoch, _, _) =>
              isWithdrawalExpired(withdrawalEpoch)
          }
      }.filter { case (_, withdrawalList) => withdrawalList.nonEmpty }

      val expiredWithdrawals = existingWithdrawals.map {
        case (address, withdrawals) =>
          address -> withdrawals.filter {
            case PendingDelegatedStakeWithdrawal(_, _, _, withdrawalEpoch, _, _) =>
              isWithdrawalExpired(withdrawalEpoch)
          }
      }.filter { case (_, withdrawalList) => withdrawalList.nonEmpty }

      (
        existingDelegatedStakes,
        unexpiredWithdrawals,
        expiredWithdrawals
      )
    }
  }
}
