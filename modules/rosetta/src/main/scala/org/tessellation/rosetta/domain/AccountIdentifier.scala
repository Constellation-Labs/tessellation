package org.tessellation.rosetta.domain

import org.tessellation.ext.derevo.magnoliaCustomizable.snakeCaseConfiguration
import org.tessellation.schema.address.Address

import derevo.cats.eqv
import derevo.circe.magnolia.{customizableDecoder, customizableEncoder}
import derevo.derive

@derive(eqv, customizableDecoder, customizableEncoder)
case class AccountIdentifier(
  address: Address,
  subAccount: Option[SubAccountIdentifier]
)

@derive(eqv, customizableDecoder, customizableEncoder)
case class SubAccountIdentifier(
  address: Address
)
