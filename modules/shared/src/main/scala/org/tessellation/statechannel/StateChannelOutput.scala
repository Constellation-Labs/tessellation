package org.tessellation.statechannel

import org.tessellation.schema.address.Address
import org.tessellation.security.signature.Signed

import derevo.cats.show
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

@derive(encoder, decoder, show)
case class StateChannelOutput(
  address: Address,
  snapshotBinary: Signed[StateChannelSnapshotBinary]
)
