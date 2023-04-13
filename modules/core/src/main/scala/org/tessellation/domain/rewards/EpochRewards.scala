package org.tessellation.domain.rewards

import scala.collection.immutable.SortedMap

import org.tessellation.schema.GlobalIncrementalSnapshot
import org.tessellation.schema.balance.Amount
import org.tessellation.schema.epoch.EpochProgress
import org.tessellation.sdk.domain.rewards.Rewards

trait EpochRewards[F[_]] extends Rewards[F, GlobalIncrementalSnapshot] {
  def getAmountByEpoch(epochProgress: EpochProgress, rewardsPerEpoch: SortedMap[EpochProgress, Amount]): Amount
}
