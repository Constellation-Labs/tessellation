package org.tessellation.sdk.domain

import cats.syntax.bifunctor._

import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.trust.{PeerObservationAdjustmentUpdate, TrustValueRefined, TrustValueRefinement}
import org.tessellation.security.hex.Hex

import eu.timepit.refined.refineV
import fs2.data.csv.generic.semiauto.deriveRowDecoder
import fs2.data.csv.{CellDecoder, DecoderError}

object trust {
  implicit val peerIdDecoder: CellDecoder[PeerId] = CellDecoder.stringDecoder.map(Hex(_)).map(PeerId(_))

  implicit val trustValueRefinedDecoder: CellDecoder[TrustValueRefined] =
    CellDecoder.doubleDecoder.emap(refineV[TrustValueRefinement](_).leftMap(new DecoderError(_)))

  implicit val rowDecoder = deriveRowDecoder[PeerObservationAdjustmentUpdate]
}
