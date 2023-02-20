package org.tessellation.rosetta.domain.api.construction

import cats.data.NonEmptyList

import org.tessellation.ext.derevo.magnoliaCustomizable.snakeCaseConfiguration
import org.tessellation.rosetta.domain.operation.Operation
import org.tessellation.rosetta.domain.{AccountIdentifier, NetworkIdentifier}
import org.tessellation.security.hex.Hex

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
