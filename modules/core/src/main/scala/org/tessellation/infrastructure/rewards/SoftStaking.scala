package org.tessellation.infrastructure.rewards

import cats.data.NonEmptyMap

import org.tessellation.config.types.SoftStakingConfig
import org.tessellation.domain.rewards._
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Amount

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.{NonNegInt, NonNegLong}

object SoftStaking {

  def make(config: SoftStakingConfig): SoftStaking = new SoftStaking {
    def getAddress: Address = config.address

    def weight(softStakingNodes: NonNegInt)(
      ignore: Set[Address] = Set.empty[Address]
    )(distribution: NonEmptyMap[Address, Amount]): Either[RewardsError, NonEmptyMap[Address, Amount]] =
      if (softStakingNodes.value == 0) Right(distribution)
      else {
        for {
          fullNodesPercentage <- NonNegInt
            .from(100 - config.softNodesPercentage)
            .left
            .map(NumberRefinementPredicatedFailure)

          softStakingNodesWithTestnet = softStakingNodes + config.testnetNodes

          withoutIgnored <- NonEmptyMap
            .fromMap(ignore.foldLeft(distribution.toSortedMap) {
              case (acc, addressToIgnore) => acc - addressToIgnore
            })
            .toRight[RewardsError](IgnoredAllAddressesDuringWeighting)

          ignored <- NonEmptyMap
            .fromMap(withoutIgnored.keys.foldLeft(distribution.toSortedMap) {
              case (acc, nonIgnoredAddress) => acc - nonIgnoredAddress
            })
            .toRight[RewardsError](IgnoredAllAddressesDuringWeighting)

          fullNodes <- NonNegInt
            .from(withoutIgnored.length)
            .left
            .map(NumberRefinementPredicatedFailure)

          weightedSum <- NonNegInt
            .from(
              ((config.softNodesPercentage * softStakingNodesWithTestnet) / 100) + ((fullNodesPercentage * fullNodes) / 100)
            )
            .left
            .map(NumberRefinementPredicatedFailure)

          rewards = distribution.toSortedMap.values.map(_.value.toLong).sum

          perSoftNode <- NonNegLong
            .from((config.softNodesPercentage * rewards) / weightedSum)
            .left
            .map(NumberRefinementPredicatedFailure)

          perFullNode <- NonNegLong
            .from((fullNodesPercentage * rewards) / weightedSum)
            .left
            .map(NumberRefinementPredicatedFailure)

          totalSoftStakingReward <- NonNegLong
            .from(perSoftNode * softStakingNodes)
            .map(Amount(_))
            .left
            .map(NumberRefinementPredicatedFailure)

          totalTestnetReward <- NonNegLong
            .from(perSoftNode * config.testnetNodes)
            .map(Amount(_))
            .left
            .map(NumberRefinementPredicatedFailure)

          weighted = withoutIgnored.transform { case (_, _) => Amount(perFullNode) } ++ ignored
        } yield weighted.add(getAddress -> totalSoftStakingReward).add(config.testnetAddress -> totalTestnetReward)
      }
  }

}
