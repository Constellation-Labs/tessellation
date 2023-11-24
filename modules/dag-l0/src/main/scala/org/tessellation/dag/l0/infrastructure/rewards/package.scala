package org.tessellation.dag.l0.infrastructure

import cats.data.StateT

import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Amount

package object rewards {

  type DistributionState[F[_]] = StateT[F, Amount, List[(Address, Amount)]]

}
