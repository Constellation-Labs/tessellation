package io.constellationnetwork.statechannel

import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.security.signature.Signed

import derevo.cats.show
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

@derive(encoder, decoder, show)
case class StateChannelOutput(
  address: Address,
  snapshotBinary: Signed[StateChannelSnapshotBinary]
)
