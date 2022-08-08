package org.tessellation.infrastructure

import cats.data.StateT

import org.tessellation.domain.rewards.RewardsDistributor
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Amount

package object rewards {

  type DistributionState[F[_]] = StateT[F, Amount, List[(Address, Amount)]]

  type SimpleRewardsDistributor = RewardsDistributor[Either[ArithmeticException, *]]

}
