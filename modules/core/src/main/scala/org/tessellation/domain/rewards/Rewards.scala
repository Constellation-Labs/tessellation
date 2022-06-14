package org.tessellation.domain.rewards

import cats.data.NonEmptySet

import scala.collection.immutable.SortedSet
import scala.util.control.NoStackTrace

import org.tessellation.dag.snapshot.epoch.EpochProgress
import org.tessellation.schema.ID.Id
import org.tessellation.schema.transaction.RewardTransaction

sealed trait RewardsError extends NoStackTrace
case class NumberRefinementPredicatedFailure(reason: String) extends RewardsError
case object IgnoredAllAddressesDuringWeighting extends RewardsError

trait Rewards[F[_]] {

  def calculateRewards(
    epochProgress: EpochProgress,
    facilitators: NonEmptySet[Id]
  ): F[SortedSet[RewardTransaction]]
}
