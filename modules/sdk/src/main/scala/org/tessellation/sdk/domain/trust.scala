package org.tessellation.sdk.domain

import org.tessellation.schema.trust._

import fs2.data.csv.RowDecoder
import fs2.data.csv.generic.semiauto.deriveRowDecoder

object trust {

  implicit val rowDecoder: RowDecoder[PeerObservationAdjustmentUpdate] = deriveRowDecoder[PeerObservationAdjustmentUpdate]

}
