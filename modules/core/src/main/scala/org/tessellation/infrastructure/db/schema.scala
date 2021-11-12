package org.tessellation.infrastructure.db

import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Balance

object schema {
  case class StoredAddress(address: Address, balance: Balance)
}
