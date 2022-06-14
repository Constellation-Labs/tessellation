package org.tessellation.domain.rewards

import cats.data.NonEmptyMap

import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Amount

import eu.timepit.refined.types.numeric.NonNegInt

trait SoftStaking {
  def getAddress: Address

  def weight(softStakingNodes: NonNegInt)(ignore: Set[Address])(
    distribution: NonEmptyMap[Address, Amount]
  ): Either[RewardsError, NonEmptyMap[Address, Amount]]
}
