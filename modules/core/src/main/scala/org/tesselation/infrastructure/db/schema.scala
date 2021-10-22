package org.tesselation.infrastructure.db

import org.tesselation.schema.address.Address
import org.tesselation.schema.balance.Balance

object schema {
  case class StoredAddress(address: Address, balance: Balance)
}
