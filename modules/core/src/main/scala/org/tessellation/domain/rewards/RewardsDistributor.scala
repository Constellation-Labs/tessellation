package org.tessellation.domain.rewards

import cats.data.NonEmptySet

import org.tessellation.dag.snapshot.epoch.EpochProgress
import org.tessellation.infrastructure.rewards.DistributionState
import org.tessellation.schema.ID.Id

trait RewardsDistributor[F[_]] {

  def distribute(epochProgress: EpochProgress, facilitators: NonEmptySet[Id]): DistributionState[F]

}
