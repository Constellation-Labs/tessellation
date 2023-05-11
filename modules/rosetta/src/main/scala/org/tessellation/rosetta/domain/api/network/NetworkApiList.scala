package org.tessellation.rosetta.domain.api.network

import org.tessellation.ext.derevo.magnoliaCustomizable.snakeCaseConfiguration
import org.tessellation.rosetta.domain.NetworkIdentifier

import derevo.circe.magnolia.customizableEncoder
import derevo.derive

object NetworkApiList {
  @derive(customizableEncoder)
  case class Response(networkIdentifiers: List[NetworkIdentifier])
}
