package io.constellationnetwork.currency.l1.http

import cats.syntax.option._

import io.constellationnetwork.currency.schema.EstimatedFee
import io.constellationnetwork.currency.schema.EstimatedFee.{Estimated, Zero}
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Amount
import io.constellationnetwork.security.hash.Hash

import io.circe.Encoder

case class EstimatedFeeResponse(fee: Amount, address: Option[Address], updateHash: Hash)

object EstimatedFeeResponse {
  // Explicit encoder instead of the magnolia derivation (`@derive(encoder)`): the CI Build JARs
  // job crashes the scalac 2.13.18 backend emitting this class's derived encoder ("an unexpected
  // type representation reached the compiler backend ... magnolia.Param[...] => Return"; the
  // compiler asks to file a scala/bug). Same JSON shape and key order as the derived instance.
  implicit val encoder: Encoder[EstimatedFeeResponse] =
    Encoder.forProduct3("fee", "address", "updateHash")(r => (r.fee, r.address, r.updateHash))

  def apply(ef: EstimatedFee, updateHash: Hash): EstimatedFeeResponse =
    ef match {
      case Zero            => EstimatedFeeResponse(fee = Amount.empty, address = none, updateHash)
      case Estimated(f, a) => EstimatedFeeResponse(fee = f, address = a.some, updateHash)
    }
}
