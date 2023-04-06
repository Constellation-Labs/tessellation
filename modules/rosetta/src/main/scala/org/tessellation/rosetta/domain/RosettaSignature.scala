package org.tessellation.rosetta.domain

import org.tessellation.ext.derevo.magnoliaCustomizable.snakeCaseConfiguration
import org.tessellation.security.hex.Hex

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.{customizableDecoder, customizableEncoder}
import derevo.derive
import enumeratum.values.{StringEnumEntry, _}

@derive(customizableDecoder)
case class RosettaSignature(
  signingPayload: SigningPayload,
  publicKey: RosettaPublicKey,
  signatureType: SignatureType,
  hexBytes: Hex
)

@derive(customizableDecoder, customizableEncoder, eqv, show)
case class SigningPayload(
  accountIdentifier: AccountIdentifier,
  hexBytes: Hex,
  signatureType: SignatureType
)

@derive(eqv, show)
sealed abstract class SignatureType(val value: String) extends StringEnumEntry

object SignatureType extends StringEnum[SignatureType] with StringCirceEnum[SignatureType] {
  val values = findValues

  case object ECDSA extends SignatureType(value = "ecdsa")
}
