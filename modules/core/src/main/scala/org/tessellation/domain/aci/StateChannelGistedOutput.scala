package org.tessellation.domain.aci

import org.tessellation.kernel.Ω
import org.tessellation.schema.address.Address

case class StateChannelGistedOutput[T <: Ω](
  address: Address,
  outputGist: T,
  outputBinary: Array[Byte]
)
