package io.constellationnetwork.node.shared.domain.collateral

import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Balance

import fs2.Stream

trait LatestBalances[F[_]] {
  def getLatestBalances: F[Option[Map[Address, Balance]]]
  def getLatestBalancesStream: Stream[F, Map[Address, Balance]]
}
