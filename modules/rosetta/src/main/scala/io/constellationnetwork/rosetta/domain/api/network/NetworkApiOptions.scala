package io.constellationnetwork.rosetta.domain.api.network

import io.constellationnetwork.ext.derevo.magnoliaCustomizable.snakeCaseConfiguration
import io.constellationnetwork.rosetta.domain.NetworkIdentifier

import derevo.circe.magnolia.customizableDecoder
import derevo.derive

object NetworkApiOptions {
  @derive(customizableDecoder)
  case class Request(networkIdentifier: NetworkIdentifier)
}
