package org.tessellation.rosetta.domain

import org.tessellation.ext.derevo.magnoliaCustomizable.snakeCaseConfiguration
import org.tessellation.security.hex.Hex

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
