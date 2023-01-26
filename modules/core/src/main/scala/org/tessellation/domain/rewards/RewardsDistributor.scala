package org.tessellation.domain.rewards

import cats.data.NonEmptySet

import org.tessellation.infrastructure.rewards.DistributionState
import org.tessellation.schema.ID.Id
import org.tessellation.schema.epoch.EpochProgress

trait RewardsDistributor[F[_]] {

  def distribute(epochProgress: EpochProgress, facilitators: NonEmptySet[Id]): DistributionState[F]

}
