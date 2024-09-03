package io.constellationnetwork.rosetta.domain.api.network

import io.constellationnetwork.ext.derevo.magnoliaCustomizable.snakeCaseConfiguration
import io.constellationnetwork.rosetta.domain.NetworkIdentifier

import derevo.circe.magnolia.customizableEncoder
import derevo.derive

object NetworkApiList {
  @derive(customizableEncoder)
  case class Response(networkIdentifiers: List[NetworkIdentifier])
}
