package org.tessellation.rosetta.domain.api.construction

import cats.data.NonEmptyList

import org.tessellation.ext.derevo.magnoliaCustomizable.snakeCaseConfiguration
import org.tessellation.rosetta.domain.amount.Amount
import org.tessellation.rosetta.domain.{NetworkIdentifier, RosettaPublicKey}
import org.tessellation.schema.transaction.TransactionReference

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.{customizableDecoder, customizableEncoder}
import derevo.derive

object ConstructionMetadata {
  @derive(customizableDecoder)
  case class Request(networkIdentifier: NetworkIdentifier, publicKeys: NonEmptyList[RosettaPublicKey])

  @derive(customizableDecoder, customizableEncoder, show)
  case class Metadata(lastReference: TransactionReference)

  @derive(customizableDecoder, customizableEncoder, show)
  case class Response(metadata: Metadata, suggestedFee: Option[Amount])
  object Response {
    def fromMetadataResult(result: MetadataResult): Response =
      ConstructionMetadata.Response(Metadata(result.lastReference), result.suggestedFee)
  }

  @derive(customizableDecoder, eqv, show)
  case class MetadataResult(lastReference: TransactionReference, suggestedFee: Option[Amount])
  object MetadataResult {
    def fromResponse(resp: Response): MetadataResult =
      MetadataResult(resp.metadata.lastReference, resp.suggestedFee)
  }
}
