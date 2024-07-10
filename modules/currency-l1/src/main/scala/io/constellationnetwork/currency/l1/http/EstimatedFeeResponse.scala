package io.constellationnetwork.currency.l1.http

import cats.syntax.option._

import io.constellationnetwork.currency.schema.EstimatedFee
import io.constellationnetwork.currency.schema.EstimatedFee.{Estimated, Zero}
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Amount
import io.constellationnetwork.security.hash.Hash

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
