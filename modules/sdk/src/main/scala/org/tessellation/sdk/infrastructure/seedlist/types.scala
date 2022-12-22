package org.tessellation.sdk.infrastructure.seedlist

import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.security.hex.Hex

import fs2.data.csv.RowDecoder
import fs2.data.csv.generic.semiauto.deriveRowDecoder

object types {

  case class SeedlistCSVEntry(id: String) {

    def toPeerId: PeerId = PeerId(Hex(id))
  }

  object SeedlistCSVEntry {
    implicit val rowDecoder: RowDecoder[SeedlistCSVEntry] = deriveRowDecoder
  }

}
