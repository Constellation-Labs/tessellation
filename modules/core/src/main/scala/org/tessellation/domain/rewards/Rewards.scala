package org.tessellation.domain.rewards

import cats.data.NonEmptySet

import scala.collection.immutable.{SortedMap, SortedSet}

import org.tessellation.schema.ID.Id
import org.tessellation.schema.SnapshotOrdinal
import org.tessellation.schema.balance.Amount
import org.tessellation.schema.epoch.EpochProgress
import org.tessellation.schema.transaction.{DAGTransaction, RewardTransaction}

trait Rewards[F[_]] {

  def mintedDistribution(
    epochProgress: EpochProgress,
    facilitators: NonEmptySet[Id]
  ): F[SortedSet[RewardTransaction]]

  def feeDistribution(
    snapshotOrdinal: SnapshotOrdinal,
    transactions: SortedSet[DAGTransaction],
    facilitators: NonEmptySet[Id]
  ): F[SortedSet[RewardTransaction]]

  def getAmountByEpoch(epochProgress: EpochProgress, rewardsPerEpoch: SortedMap[EpochProgress, Amount]): Amount
}
