package org.tessellation.statechannel

import derevo.cats.eqv
import derevo.derive

@derive(eqv)
sealed trait StateChannelValidationType
object StateChannelValidationType {
  case object Full extends StateChannelValidationType
  case object Historical extends StateChannelValidationType
}
