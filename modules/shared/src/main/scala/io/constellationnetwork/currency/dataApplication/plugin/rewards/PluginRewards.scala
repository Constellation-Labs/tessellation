package io.constellationnetwork.currency.dataApplication.plugin.rewards

import cats.effect.Async

import io.constellationnetwork.currency.dataApplication.{DataCalculatedState, DataOnChainState, DataState}
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Amount

case class PluginReward(
  address: Address,
  amount: Amount
)

trait PluginRewards[F[_], POnChain <: DataOnChainState, PCalculated <: DataCalculatedState] {
  def calculateRewards(
    state: DataState[POnChain, PCalculated]
  )(implicit F: Async[F]): F[List[PluginReward]] =
    F.pure(List.empty)
}
