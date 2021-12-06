package org.tessellation.domain.aci

import org.tessellation.kernel.Ω
import org.tessellation.schema.address.Address

case class StateChannelOutput(
  address: Address,
  content: Ω,
  contentBytes: Array[Byte]
)
