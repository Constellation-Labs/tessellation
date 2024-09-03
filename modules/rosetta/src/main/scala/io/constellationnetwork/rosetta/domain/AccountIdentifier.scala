package io.constellationnetwork.rosetta.domain

import io.constellationnetwork.ext.derevo.magnoliaCustomizable.snakeCaseConfiguration
import io.constellationnetwork.schema.address.Address

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.{customizableDecoder, customizableEncoder}
import derevo.derive

@derive(eqv, customizableDecoder, customizableEncoder, show)
case class AccountIdentifier(
  address: Address,
  subAccount: Option[SubAccountIdentifier]
)

@derive(eqv, customizableDecoder, customizableEncoder, show)
case class SubAccountIdentifier(
  address: Address
)
