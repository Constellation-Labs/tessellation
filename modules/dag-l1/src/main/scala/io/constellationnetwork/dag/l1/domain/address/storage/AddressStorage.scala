package io.constellationnetwork.dag.l1.domain.address.storage

import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Balance

trait AddressStorage[F[_]] {
  def getState: F[Map[Address, Balance]]
  def getBalance(address: Address): F[Balance]
  def updateBalances(addressBalances: Map[Address, Balance]): F[Unit]
  def clean: F[Unit]
}
