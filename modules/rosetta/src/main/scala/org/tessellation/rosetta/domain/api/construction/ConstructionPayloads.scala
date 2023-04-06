package org.tessellation.rosetta.domain.api.construction

import cats.data.NonEmptyList

import org.tessellation.ext.derevo.magnoliaCustomizable.snakeCaseConfiguration
import org.tessellation.rosetta.domain.api.construction.ConstructionMetadata.MetadataResult
import org.tessellation.rosetta.domain.operation.Operation
import org.tessellation.rosetta.domain.{NetworkIdentifier, SigningPayload}
import org.tessellation.security.hex.Hex

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.{customizableDecoder, customizableEncoder}
import derevo.derive

case object ConstructionPayloads {

  @derive(customizableDecoder)
  case class Request(
    networkIdentifier: NetworkIdentifier,
    operations: NonEmptyList[Operation],
    metadata: MetadataResult
  )

  @derive(customizableEncoder, eqv, show)
  case class Response(
    unsignedTransaction: Hex,
    payloads: NonEmptyList[SigningPayload]
  )

  object Response {
    def fromPayloadsResult(result: PayloadsResult): Response = Response(
      result.unsignedTransaction,
      result.payloads
    )
  }

  @derive(eqv, show)
  case class PayloadsResult(
    unsignedTransaction: Hex,
    payloads: NonEmptyList[SigningPayload]
  )

}
