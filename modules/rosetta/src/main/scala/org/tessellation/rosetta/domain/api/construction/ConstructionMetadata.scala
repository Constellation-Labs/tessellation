package org.tessellation.rosetta.domain.api.construction

import org.tessellation.ext.derevo.magnoliaCustomizable.snakeCaseConfiguration
import org.tessellation.rosetta.domain.amount.Amount
import org.tessellation.schema.transaction.TransactionReference

import derevo.cats.show
import derevo.circe.magnolia.customizableDecoder
import derevo.derive

object ConstructionMetadata {

  @derive(customizableDecoder, show)
  case class Metadata(lastReference: TransactionReference)

  @derive(customizableDecoder, show)
  case class Response(
    metadata: Metadata,
    suggestedFee: Option[Amount]
  )

  @derive(customizableDecoder, show)
  case class MetadataResult(
    metadata: Metadata,
    suggestedFee: Option[Amount]
  )
}
