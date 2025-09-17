package io.constellationnetwork.dag.l0.infrastructure.rewards

import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.traverse._

import io.constellationnetwork.dag.l0.infrastructure.snapshot.event.GlobalSnapshotEvent
import io.constellationnetwork.node.shared.domain.rewards.Rewards
import io.constellationnetwork.node.shared.infrastructure.delegatedStake.{RewardsInfoCalculator, RewardsInfoStorage}
import io.constellationnetwork.node.shared.infrastructure.snapshot.DelegatedRewardsDistributor
import io.constellationnetwork.schema.delegatedStake.RewardsInfo
import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo, GlobalSnapshotStateProof}

case class RewardsService[F[_]: Async](
  classicRewards: Rewards[F, GlobalSnapshotStateProof, GlobalIncrementalSnapshot, GlobalSnapshotEvent],
  delegatedRewards: DelegatedRewardsDistributor[F],
  rewardsInfoCalculator: RewardsInfoCalculator[F],
  rewardsInfoStorage: RewardsInfoStorage[F]
) {
  def calculateAndStoreRewardsInfo(lastSnapshot: GlobalIncrementalSnapshot, lastSnapshotInfo: GlobalSnapshotInfo): F[Option[RewardsInfo]] =
    for {
      maybeRewardsInfo <- rewardsInfoCalculator.calculateRewardsInfo(lastSnapshot, lastSnapshotInfo)
      _ <- maybeRewardsInfo.traverse(rewardsInfoStorage.storeRewardsInfo)
    } yield maybeRewardsInfo
}
