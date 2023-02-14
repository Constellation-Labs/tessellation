package org.tessellation.rosetta.domain.api.construction

import cats.data.NonEmptyList

import org.tessellation.ext.derevo.magnoliaCustomizable.snakeCaseConfiguration
import org.tessellation.rosetta.domain._
import org.tessellation.rosetta.domain.operation.Operation

import derevo.circe.magnolia.{customizableDecoder, customizableEncoder}
import derevo.derive

case object ConstructionPreprocess {
  @derive(customizableDecoder)
  case class Request(
    networkIdentifier: NetworkIdentifier,
    operations: List[Operation]
  )

  @derive(customizableEncoder)
  case class Response(
    requiredPublicKeys: Option[NonEmptyList[AccountIdentifier]]
  )
}
