package org.tessellation.sdk.domain.fork

import org.tessellation.schema.SnapshotOrdinal
import org.tessellation.security.hash.Hash

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

@derive(eqv, decoder, encoder, show)
case class ForkInfo(ordinal: SnapshotOrdinal, hash: Hash)
