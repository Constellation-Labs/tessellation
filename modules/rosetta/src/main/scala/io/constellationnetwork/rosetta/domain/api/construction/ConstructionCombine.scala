package io.constellationnetwork.rosetta.domain.api.construction

import cats.data.NonEmptyList

import io.constellationnetwork.ext.derevo.magnoliaCustomizable.snakeCaseConfiguration
import io.constellationnetwork.rosetta.domain.{NetworkIdentifier, RosettaPublicKey, RosettaSignature}
import io.constellationnetwork.security.hex.Hex

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
