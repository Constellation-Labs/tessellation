package org.tessellation.sdk.domain.collateral

import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Balance

trait LatestBalances[F[_]] {
  def getLatestBalances: F[Option[Map[Address, Balance]]]
}
