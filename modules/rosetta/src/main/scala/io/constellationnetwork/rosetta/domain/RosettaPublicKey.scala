package io.constellationnetwork.rosetta.domain

import io.constellationnetwork.ext.derevo.magnoliaCustomizable.snakeCaseConfiguration
import io.constellationnetwork.security.hex.Hex

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.customizableDecoder
import derevo.derive
import enumeratum.values._

@derive(customizableDecoder, show)
case class RosettaPublicKey(
  hexBytes: Hex,
  curveType: CurveType
)

@derive(eqv, show)
sealed abstract class CurveType(val value: String) extends StringEnumEntry
object CurveType extends StringEnum[CurveType] with StringCirceEnum[CurveType] {
  val values = findValues

  case object SECP256K1 extends CurveType(value = "secp256k1")
}
