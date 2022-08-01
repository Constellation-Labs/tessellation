package org.tessellation.infrastructure.rewards

import cats.data.NonEmptyMap

import org.tessellation.config.types.StardustConfig
import org.tessellation.domain.rewards.{NumberRefinementPredicatedFailure, RewardsError, StardustCollective}
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Amount

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong

object StardustCollective {

  def make(config: StardustConfig): StardustCollective = new StardustCollective {
    def getAddress: Address = config.address

    def weight(
      ignore: Set[Address]
    )(distribution: NonEmptyMap[Address, Amount]): Either[RewardsError, NonEmptyMap[Address, Amount]] = {

      val stardustWeights = (distribution.toSortedMap -- ignore).transform {
        case (a, reward) =>
          (reward.value * config.percentage) / 100
      }

      val weighted = distribution.transform {
        case (address, reward) if ignore.contains(address) => reward.value.toLong
        case (address, reward)                             => reward.value.toLong - stardustWeights(address)
      }.nonEmptyTraverse(reward => NonNegLong.from(reward).map(Amount(_)))

      NonNegLong
        .from(stardustWeights.values.sum)
        .map(Amount(_))
        .flatMap { totalStardustReward =>
          weighted.map(_.add(getAddress -> totalStardustReward))
        }
        .left
        .map(NumberRefinementPredicatedFailure)
    }
  }

}
