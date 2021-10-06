package org.tesselation.infrastructure.db

import org.tesselation.schema.address.{Address, Balance}

object schema {
  case class StoredAddress(address: Address, balance: Balance)
}
