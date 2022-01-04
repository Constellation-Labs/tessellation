package org.tessellation.dag.l1.domain.address.storage

import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Balance

trait AddressStorage[F[_]] {
  def getBalance(address: Address): F[Balance]
  def updateBalances(addressBalances: Map[Address, Balance]): F[Unit]
}
