package org.tessellation.rosetta.domain.api.construction

import org.tessellation.ext.derevo.magnoliaCustomizable.snakeCaseConfiguration
import org.tessellation.rosetta.domain.{AccountIdentifier, NetworkIdentifier, RosettaPublicKey}

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
