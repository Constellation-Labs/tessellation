package org.tessellation.domain.rewards

import cats.data.NonEmptyMap

import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Amount

trait DTM {
  def getAddress: Address

  def weight(ignore: Set[Address])(
    distribution: NonEmptyMap[Address, Amount]
  ): Either[RewardsError, NonEmptyMap[Address, Amount]]
}
