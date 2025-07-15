package io.constellationnetwork.domain.allowance_list

import cats.Order
import cats.syntax.all._

import io.constellationnetwork.schema.peer.PeerId

import fs2.data.csv._

case class AllowanceListEntry(
  peerId: PeerId
)

object AllowanceListEntry {

  implicit object AllowanceListRowDecoder extends RowDecoder[AllowanceListEntry] {
    def apply(row: Row): DecoderResult[AllowanceListEntry] =
      row.values.size match {
        case 1 =>
          CellDecoder[PeerId].apply(row.values.head).map {
            AllowanceListEntry(_)
          }
        case _ => Left(new DecoderError(s"Rows must have 1 field, but found ${row.values.size}"))
      }
  }

  implicit val order: Order[AllowanceListEntry] = Order[PeerId].contramap(_.peerId)
  implicit val ordering: Ordering[AllowanceListEntry] = order.toOrdering
}
