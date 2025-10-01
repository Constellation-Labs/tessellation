package io.constellationnetwork.node.shared.infrastructure.delegatedStake

import cats.effect.{Async, Ref}
import cats.syntax.functor._

import io.constellationnetwork.schema.delegatedStake.RewardsInfo

trait RewardsInfoStorage[F[_]] {
  def getRewardsInfo: F[Option[RewardsInfo]]
  def storeRewardsInfo(rewardsInfo: RewardsInfo): F[Unit]
}

object RewardsInfoStorage {
  def make[F[_]: Async]: F[RewardsInfoStorage[F]] =
    Ref[F]
      .of(Option.empty[RewardsInfo])
      .map(rewardsInfoRef =>
        new RewardsInfoStorage[F] {
          def getRewardsInfo: F[Option[RewardsInfo]] = rewardsInfoRef.get

          def storeRewardsInfo(rewardsInfo: RewardsInfo): F[Unit] = rewardsInfoRef.set(Some(rewardsInfo))
        }
      )
}
