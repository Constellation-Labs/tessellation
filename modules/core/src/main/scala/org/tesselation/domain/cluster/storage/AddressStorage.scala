package org.tesselation.domain.cluster.storage

import org.tesselation.infrastructure.db.schema
import org.tesselation.schema.address.{Address, Balance}

trait AddressStorage[F[_]] {
  def getBalance(address: Address): F[Balance]

  def updateBalance(address: Address, balance: Balance): F[schema.StoredAddress]

  def clearBalance(address: Address): F[Unit]
}
