package io.constellationnetwork.rosetta.domain.api.construction

import cats.data.NonEmptyList

import io.constellationnetwork.ext.derevo.magnoliaCustomizable.snakeCaseConfiguration
import io.constellationnetwork.rosetta.domain._
import io.constellationnetwork.rosetta.domain.operation.Operation

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
