package org.tessellation.sdk.infrastructure.whitelisting

import org.tessellation.schema.peer.PeerId
import org.tessellation.security.hex.Hex

import fs2.data.csv.RowDecoder
import fs2.data.csv.generic.semiauto.deriveRowDecoder

object types {

  case class WhitelistingCSVEntry(id: String) {

    def toPeerId: PeerId = PeerId(Hex(id))
  }

  object WhitelistingCSVEntry {
    implicit val rowDecoder: RowDecoder[WhitelistingCSVEntry] = deriveRowDecoder
  }

}
