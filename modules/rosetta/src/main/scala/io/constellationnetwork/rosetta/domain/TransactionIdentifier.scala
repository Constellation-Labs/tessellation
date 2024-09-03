package io.constellationnetwork.rosetta.domain

import io.constellationnetwork.ext.derevo.magnoliaCustomizable.snakeCaseConfiguration
import io.constellationnetwork.security.hash.Hash

import derevo.cats.eqv
import derevo.circe.magnolia.customizableEncoder
import derevo.derive

@derive(eqv, customizableEncoder)
case class TransactionIdentifier(
  hash: Hash
)
