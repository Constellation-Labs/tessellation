package io.constellationnetwork.dag.l0.infrastructure.rewards

import io.constellationnetwork.dag.l0.infrastructure.snapshot.event.GlobalSnapshotEvent
import io.constellationnetwork.node.shared.domain.rewards.Rewards
import io.constellationnetwork.node.shared.infrastructure.snapshot.DelegatedRewardsDistributor
import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, GlobalSnapshotStateProof}

case class RewardsService[F[_]](
  classicRewards: Rewards[F, GlobalSnapshotStateProof, GlobalIncrementalSnapshot, GlobalSnapshotEvent],
  delegatedRewards: DelegatedRewardsDistributor[F]
)
