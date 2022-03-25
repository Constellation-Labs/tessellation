package org.tessellation.domain.dag

import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Balance

trait DAGService[F[_]] {
  def getBalance(address: Address): F[Balance]
  def getTotalSupply: F[BigInt]
}
