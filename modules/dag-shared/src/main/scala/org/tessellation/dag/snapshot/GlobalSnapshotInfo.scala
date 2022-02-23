package org.tessellation.dag.snapshot

import org.tessellation.schema.address.Address
import org.tessellation.security.hash.Hash

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

@derive(encoder, decoder, eqv, show)
case class GlobalSnapshotInfo(
  lastStateChannelSnapshotHashes: Map[Address, Hash]
)
