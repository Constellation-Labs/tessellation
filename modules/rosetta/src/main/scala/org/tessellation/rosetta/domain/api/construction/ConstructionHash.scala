package org.tessellation.rosetta.domain.api.construction

import org.tessellation.ext.derevo.magnoliaCustomizable.snakeCaseConfiguration
import org.tessellation.rosetta.domain.{NetworkIdentifier, TransactionIdentifier}
import org.tessellation.security.hex.Hex

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
