package org.tessellation.domain.cluster.storage

import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Balance

trait AddressStorage[F[_]] {
  def getBalance(address: Address): F[Balance]

  def updateBalance(address: Address, balance: Balance): F[(Address, Balance)]

  def clearBalance(address: Address): F[Unit]
}
