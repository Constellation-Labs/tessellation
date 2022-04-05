package org.tessellation.domain.snapshot.rewards

import cats.data.NonEmptyList

import org.tessellation.schema.ID.Id
import org.tessellation.schema.transaction.RewardTransaction

object Rewards {

  def calculateRewards(facilitators: NonEmptyList[Id]): Set[RewardTransaction] =
    Set.empty

}
