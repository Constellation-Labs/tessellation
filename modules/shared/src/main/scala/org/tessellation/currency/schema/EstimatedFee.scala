package org.tessellation.currency.schema

import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Amount

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
