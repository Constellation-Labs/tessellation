package org.tessellation.domain.aci

import org.tessellation.schema.address.Address

case class StateChannelInput(address: Address, bytes: Array[Byte])
