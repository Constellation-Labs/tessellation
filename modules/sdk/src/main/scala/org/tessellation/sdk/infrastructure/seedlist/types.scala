package org.tessellation.sdk.infrastructure.seedlist

import cats.syntax.either._

import org.tessellation.schema.hex.HexString128Spec
import org.tessellation.schema.peer.PeerId

import eu.timepit.refined.refineV
import fs2.data.csv.{DecoderError, RowDecoder}
import io.estatico.newtype.macros.newtype

object types {

  @newtype
  case class SeedlistCSVEntry(peerId: PeerId)

  object SeedlistCSVEntry {
    implicit val rowDecoder: RowDecoder[SeedlistCSVEntry] = RowDecoder.instance { row =>
      refineV[HexString128Spec](row.values.head)
        .map(hex => SeedlistCSVEntry(PeerId(hex)))
        .leftMap(err => new DecoderError(err, row.line))
    }
  }

}
