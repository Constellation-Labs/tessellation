package org.tesselation.domain.aci

import org.tesselation.schema.address.Address

case class StateChannelInput(address: Address, bytes: Array[Byte])
