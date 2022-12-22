package org.tessellation.schema.statechannels

import org.tessellation.schema.address.Address
import org.tessellation.schema.security.signature.Signed

import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

@derive(encoder, decoder)
case class StateChannelOutput(
  address: Address,
  snapshot: Signed[StateChannelSnapshotBinary]
)
