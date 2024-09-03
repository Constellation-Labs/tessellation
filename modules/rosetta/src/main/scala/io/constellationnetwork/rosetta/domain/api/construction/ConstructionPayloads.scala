package io.constellationnetwork.rosetta.domain.api.construction

import cats.data.NonEmptyList

import io.constellationnetwork.ext.derevo.magnoliaCustomizable.snakeCaseConfiguration
import io.constellationnetwork.rosetta.domain.operation.Operation
import io.constellationnetwork.rosetta.domain.{NetworkIdentifier, SigningPayload}
import io.constellationnetwork.security.hex.Hex

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.{customizableDecoder, customizableEncoder}
import derevo.derive

case object ConstructionPayloads {

  @derive(customizableDecoder)
  case class Request(
    networkIdentifier: NetworkIdentifier,
    operations: NonEmptyList[Operation],
    metadata: ConstructionMetadata.Response
  )

  @derive(customizableEncoder, eqv, show)
  case class PayloadsResult(
    unsignedTransaction: Hex,
    payloads: NonEmptyList[SigningPayload]
  )

}
