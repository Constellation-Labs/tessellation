package io.constellationnetwork.dag.l0.infrastructure

import cats.data.StateT

import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Amount

package object rewards {

  type DistributionState[F[_]] = StateT[F, Amount, List[(Address, Amount)]]

}
