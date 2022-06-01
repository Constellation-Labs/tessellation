package org.tessellation.domain.snapshot.rewards

import cats.data.NonEmptySet

import scala.collection.immutable.SortedSet

import org.tessellation.schema.ID.Id
import org.tessellation.schema.transaction.RewardTransaction

object Rewards {

  def calculateRewards(facilitators: NonEmptySet[Id]): SortedSet[RewardTransaction] =
    SortedSet.empty

}
