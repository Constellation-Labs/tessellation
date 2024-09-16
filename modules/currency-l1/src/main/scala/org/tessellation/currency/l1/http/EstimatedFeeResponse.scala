package org.tessellation.currency.l1.http

import cats.syntax.option._

import org.tessellation.currency.schema.EstimatedFee
import org.tessellation.currency.schema.EstimatedFee.{Estimated, Zero}
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Amount
import org.tessellation.security.hash.Hash

import derevo.circe.magnolia.encoder
import derevo.derive

@derive(encoder)
case class EstimatedFeeResponse(fee: Amount, address: Option[Address], updateHash: Hash)

object EstimatedFeeResponse {
  def apply(ef: EstimatedFee, updateHash: Hash): EstimatedFeeResponse =
    ef match {
      case Zero            => EstimatedFeeResponse(fee = Amount.empty, address = none, updateHash)
      case Estimated(f, a) => EstimatedFeeResponse(fee = f, address = a.some, updateHash)
    }
}
