package org.tesselation.domain.cluster.storage

import org.tesselation.schema.address.Address
import org.tesselation.schema.balance.Balance

trait AddressStorage[F[_]] {
  def getBalance(address: Address): F[Balance]

  def updateBalance(address: Address, balance: Balance): F[(Address, Balance)]

  def clearBalance(address: Address): F[Unit]
}
