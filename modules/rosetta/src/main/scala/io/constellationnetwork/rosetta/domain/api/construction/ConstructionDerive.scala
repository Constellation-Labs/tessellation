package io.constellationnetwork.rosetta.domain.api.construction

import io.constellationnetwork.ext.derevo.magnoliaCustomizable.snakeCaseConfiguration
import io.constellationnetwork.rosetta.domain.{AccountIdentifier, NetworkIdentifier, RosettaPublicKey}

import derevo.circe.magnolia.{customizableDecoder, customizableEncoder}
import derevo.derive

case object ConstructionDerive {
  @derive(customizableDecoder)
  case class Request(
    networkIdentifier: NetworkIdentifier,
    publicKey: RosettaPublicKey
  )

  @derive(customizableEncoder)
  case class Response(
    accountIdentifier: AccountIdentifier
  )
}
