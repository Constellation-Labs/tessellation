package org.tessellation.currency.l1.http

import cats.syntax.option._

import org.tessellation.currency.schema.EstimatedFee
import org.tessellation.currency.schema.EstimatedFee.{Estimated, Zero}
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Amount

import derevo.circe.magnolia.encoder
import derevo.derive

@derive(encoder)
case class EstimatedFeeResponse(fee: Amount, address: Option[Address])

object EstimatedFeeResponse {
  def apply(ef: EstimatedFee): EstimatedFeeResponse =
    ef match {
      case Zero            => EstimatedFeeResponse(fee = Amount.empty, address = none)
      case Estimated(f, a) => EstimatedFeeResponse(fee = f, address = a.some)
    }
}
