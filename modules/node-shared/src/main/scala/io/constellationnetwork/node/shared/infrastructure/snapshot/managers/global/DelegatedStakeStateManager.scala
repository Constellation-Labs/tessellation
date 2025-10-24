package io.constellationnetwork.node.shared.infrastructure.snapshot.managers.global

import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.schema._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.delegatedStake._
import io.constellationnetwork.schema.epoch.EpochProgress

trait DelegatedStakeStateManager[F[_]] {
  def acceptDelegatedStakes(
    lastSnapshotContext: GlobalSnapshotInfo,
    epochProgress: EpochProgress,
    withdrawalTimeLimit: EpochProgress
  ): (
    SortedMap[Address, SortedSet[DelegatedStakeRecord]],
    SortedMap[Address, SortedSet[PendingDelegatedStakeWithdrawal]],
    SortedMap[Address, SortedSet[PendingDelegatedStakeWithdrawal]]
  )
}

object DelegatedStakeStateManager {

  def make[F[_]](): DelegatedStakeStateManager[F] = new DelegatedStakeStateManager[F] {

    def acceptDelegatedStakes(
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
            case PendingDelegatedStakeWithdrawal(_, _, _, withdrawalEpoch) =>
              isWithdrawalExpired(withdrawalEpoch)
          }
      }.filter { case (_, withdrawalList) => withdrawalList.nonEmpty }

      val expiredWithdrawals = existingWithdrawals.map {
        case (address, withdrawals) =>
          address -> withdrawals.filter {
            case PendingDelegatedStakeWithdrawal(_, _, _, withdrawalEpoch) =>
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
