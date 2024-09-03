package io.constellationnetwork.node.shared.domain.fork

import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.security.hash.Hash

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

@derive(eqv, decoder, encoder, show)
case class ForkInfo(ordinal: SnapshotOrdinal, hash: Hash)
