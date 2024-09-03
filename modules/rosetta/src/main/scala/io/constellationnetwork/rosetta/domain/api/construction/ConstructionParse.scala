package io.constellationnetwork.rosetta.domain.api.construction

import cats.data.NonEmptyList

import io.constellationnetwork.ext.derevo.magnoliaCustomizable.snakeCaseConfiguration
import io.constellationnetwork.rosetta.domain.operation.Operation
import io.constellationnetwork.rosetta.domain.{AccountIdentifier, NetworkIdentifier}
import io.constellationnetwork.security.hex.Hex

import derevo.circe.magnolia.{customizableDecoder, customizableEncoder}
import derevo.derive

case object ConstructionParse {
  @derive(customizableDecoder)
  case class Request(networkIdentifier: NetworkIdentifier, signed: Boolean, transaction: Hex)

  @derive(customizableEncoder)
  case class Response(operations: List[Operation], accountIdentifierSigners: Option[NonEmptyList[AccountIdentifier]])

  object Response {
    def fromParseResult(result: ParseResult) = Response(result.operations.toList, result.accountIdentifierSigners)
  }

  case class ParseResult(
    operations: NonEmptyList[Operation],
    accountIdentifierSigners: Option[NonEmptyList[AccountIdentifier]]
  )
}
