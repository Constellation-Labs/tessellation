package org.tessellation.dag.snapshot

import cats.Order
import cats.kernel.{Next, PartialOrder, PartialPrevious}
import cats.syntax.contravariant._
import cats.syntax.semigroup._

import derevo.cats.show
import derevo.derive
import eu.timepit.refined.auto._
import eu.timepit.refined.cats._
import eu.timepit.refined.numeric.NonNegative
import eu.timepit.refined.refineV
import eu.timepit.refined.types.numeric.NonNegLong

@derive(show)
case class SnapshotOrdinal(value: NonNegLong)

object SnapshotOrdinal {
  val MinValue: SnapshotOrdinal = SnapshotOrdinal(NonNegLong.MinValue)

  implicit val order: Order[SnapshotOrdinal] = Order[NonNegLong].contramap(_.value)

  implicit val next: Next[SnapshotOrdinal] = new Next[SnapshotOrdinal] {
    def next(a: SnapshotOrdinal): SnapshotOrdinal = SnapshotOrdinal(a.value |+| NonNegLong(1L))
    def partialOrder: PartialOrder[SnapshotOrdinal] = order
  }

  implicit val partialPrevious: PartialPrevious[SnapshotOrdinal] = new PartialPrevious[SnapshotOrdinal] {
    def partialOrder: PartialOrder[SnapshotOrdinal] = order

    def partialPrevious(a: SnapshotOrdinal): Option[SnapshotOrdinal] =
      refineV[NonNegative].apply[Long](a.value.value |+| -1).toOption.map(r => SnapshotOrdinal(r))
  }
}
