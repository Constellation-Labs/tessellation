package org.tessellation.schema

import cats.Order
import cats.kernel._
import cats.syntax.semigroup._

import derevo.cats.{order, show}
import derevo.derive
import eu.timepit.refined.cats._
import eu.timepit.refined.numeric.NonNegative
import eu.timepit.refined.refineV
import eu.timepit.refined.types.numeric.NonNegLong
import io.circe.{Decoder, Encoder}

@derive(order, show)
case class SnapshotOrdinal(value: NonNegLong)

object SnapshotOrdinal {
  val MinValue: SnapshotOrdinal = SnapshotOrdinal(NonNegLong.MinValue)

  implicit val next: Next[SnapshotOrdinal] = new Next[SnapshotOrdinal] {
    def next(a: SnapshotOrdinal): SnapshotOrdinal = SnapshotOrdinal(a.value |+| NonNegLong(1L))
    def partialOrder: PartialOrder[SnapshotOrdinal] = Order[SnapshotOrdinal]
  }

  implicit val partialPrevious: PartialPrevious[SnapshotOrdinal] = new PartialPrevious[SnapshotOrdinal] {
    def partialOrder: PartialOrder[SnapshotOrdinal] = Order[SnapshotOrdinal]

    def partialPrevious(a: SnapshotOrdinal): Option[SnapshotOrdinal] =
      refineV[NonNegative].apply[Long](a.value.value |+| -1).toOption.map(r => SnapshotOrdinal(r))
  }

  implicit val encoder: Encoder[SnapshotOrdinal] = Encoder[NonNegLong].contramap(_.value)

  implicit val decoder: Decoder[SnapshotOrdinal] = Decoder[NonNegLong].map(SnapshotOrdinal(_))
}
