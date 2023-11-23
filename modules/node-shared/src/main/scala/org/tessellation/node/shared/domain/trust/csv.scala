package org.tessellation.node.shared.domain.trust

import org.tessellation.schema.trust._

import fs2.data.csv.RowDecoder
import fs2.data.csv.generic.semiauto.deriveRowDecoder

object csv {

  implicit val rowDecoder: RowDecoder[PeerObservationAdjustmentUpdate] = deriveRowDecoder[PeerObservationAdjustmentUpdate]

}
