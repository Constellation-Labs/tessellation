package io.constellationnetwork.dag.l0.domain.rewards

import cats.data.NonEmptySet

import io.constellationnetwork.dag.l0.infrastructure.rewards.DistributionState
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema.epoch.EpochProgress

trait RewardsDistributor[F[_]] {

  def distribute(epochProgress: EpochProgress, facilitators: NonEmptySet[Id]): DistributionState[F]

}
