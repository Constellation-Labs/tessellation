package io.constellationnetwork.currency.schema

import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Amount

import derevo.cats.show
import derevo.derive

@derive(show)
sealed trait EstimatedFee

object EstimatedFee {
  case object Zero extends EstimatedFee
  case class Estimated(fee: Amount, address: Address) extends EstimatedFee

  val empty: EstimatedFee = Zero

  def apply(fee: Amount, address: Address): EstimatedFee =
    Estimated(fee, address)
}
