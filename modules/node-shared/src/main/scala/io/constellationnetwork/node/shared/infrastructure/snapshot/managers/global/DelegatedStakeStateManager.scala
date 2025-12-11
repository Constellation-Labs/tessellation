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

        // Keep expired withdrawals in pending state if their token lock is no longer active
        finalUnexpiredWithdrawals = unexpiredWithdrawals |+| expiredWithdrawals.map {
          case (address, withdrawals) =>
            address -> withdrawals.filter { withdrawal =>
              !activeTokenLocksByRef.contains(withdrawal.tokenLockRef)
            }
        }.filter { case (_, withdrawalList) => withdrawalList.nonEmpty }

        finalExpiredWithdrawals = expiredWithdrawals.map {
          case (address, withdrawals) =>
            address -> withdrawals.filter { withdrawal =>
              activeTokenLocksByRef.contains(withdrawal.tokenLockRef)
            }
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
