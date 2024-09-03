package io.constellationnetwork.rosetta.domain.api.construction

import io.constellationnetwork.ext.derevo.magnoliaCustomizable.snakeCaseConfiguration
import io.constellationnetwork.rosetta.domain.{NetworkIdentifier, TransactionIdentifier}
import io.constellationnetwork.security.hex.Hex

import derevo.circe.magnolia.{customizableDecoder, customizableEncoder}
import derevo.derive

case object ConstructionHash {
  @derive(customizableDecoder)
  case class Request(
    networkIdentifier: NetworkIdentifier,
    signedTransaction: Hex
  )

  @derive(customizableEncoder)
  case class Response(
    transactionIdentifier: TransactionIdentifier
  )
}
