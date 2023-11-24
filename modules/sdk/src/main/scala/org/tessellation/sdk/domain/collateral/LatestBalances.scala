package org.tessellation.sdk.domain.collateral

import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Balance

import fs2.Stream

trait LatestBalances[F[_]] {
  def getLatestBalances: F[Option[Map[Address, Balance]]]
  def getLatestBalancesStream: Stream[F, Map[Address, Balance]]
}
