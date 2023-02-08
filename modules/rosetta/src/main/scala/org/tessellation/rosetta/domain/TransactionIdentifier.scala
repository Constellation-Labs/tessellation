package org.tessellation.rosetta.domain

import org.tessellation.ext.derevo.magnoliaCustomizable.snakeCaseConfiguration
import org.tessellation.security.hash.Hash

import derevo.cats.eqv
import derevo.circe.magnolia.customizableEncoder
import derevo.derive

@derive(eqv, customizableEncoder)
case class TransactionIdentifier(
  hash: Hash
)
