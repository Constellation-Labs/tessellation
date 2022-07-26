package org.tessellation.dag.snapshot

import cats.Order._
import cats.kernel._
import cats.syntax.semigroup._

import org.tessellation.ext.derevo.ordering
import org.tessellation.schema._

import derevo.cats.{eqv, order, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import eu.timepit.refined.auto._
import eu.timepit.refined.cats._
import eu.timepit.refined.types.numeric.NonNegLong
import io.estatico.newtype.macros.newtype

object epoch {

  @derive(eqv, encoder, decoder, show, order, ordering)
  @newtype
  case class EpochProgress(value: NonNegLong)

  object EpochProgress {
    val MinValue: EpochProgress = EpochProgress(NonNegLong.MinValue)
    val MaxValue: EpochProgress = EpochProgress(NonNegLong.MaxValue)

    implicit val next: Next[EpochProgress] = new Next[EpochProgress] {
      def next(a: EpochProgress): EpochProgress = EpochProgress(a.value |+| NonNegLong(1L))
      def partialOrder: PartialOrder[EpochProgress] = Order[EpochProgress]
    }
  }

}
