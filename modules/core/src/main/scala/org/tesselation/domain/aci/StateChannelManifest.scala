package org.tesselation.domain.aci

import org.tesselation.schema.address.Address

import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

@derive(encoder, decoder)
case class StateChannelManifest(
  address: Address,
  cellClass: String,
  inputClass: String,
  kryoRegistrar: Map[String, Int]
)
