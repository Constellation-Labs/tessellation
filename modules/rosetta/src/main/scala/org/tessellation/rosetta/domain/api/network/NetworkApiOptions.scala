package org.tessellation.rosetta.domain.api.network

import org.tessellation.ext.derevo.magnoliaCustomizable.snakeCaseConfiguration
import org.tessellation.rosetta.domain.NetworkIdentifier

import derevo.circe.magnolia.customizableDecoder
import derevo.derive

object NetworkApiOptions {
  @derive(customizableDecoder)
  case class Request(networkIdentifier: NetworkIdentifier)
}
