package org.tessellation.rosetta.domain.api.construction

import cats.data.NonEmptyList

import org.tessellation.ext.derevo.magnoliaCustomizable.snakeCaseConfiguration
import org.tessellation.rosetta.domain.{NetworkIdentifier, RosettaPublicKey, RosettaSignature}
import org.tessellation.security.hex.Hex

import derevo.circe.magnolia.{customizableDecoder, customizableEncoder}
import derevo.derive

case object ConstructionCombine {
  @derive(customizableDecoder)
  case class Request(
    networkIdentifier: NetworkIdentifier,
    unsignedTransaction: Hex,
    signatures: NonEmptyList[RosettaSignature],
    publicKey: RosettaPublicKey
  )

  @derive(customizableEncoder)
  case class Response(
    signedTransaction: Hex
  )
}
