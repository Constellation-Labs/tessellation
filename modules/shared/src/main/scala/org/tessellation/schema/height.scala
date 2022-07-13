package org.tessellation.schema

import cats.Order._
import cats.kernel.Next
import cats.syntax.contravariant._
import cats.syntax.order._
import cats.syntax.semigroup._
import cats.{Order, PartialOrder}

import org.tessellation.ext.derevo.ordering

import derevo.cats.{eqv, order, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import derevo.scalacheck.arbitrary
import eu.timepit.refined.auto._
import eu.timepit.refined.cats._
import eu.timepit.refined.scalacheck.all._
import eu.timepit.refined.types.numeric.NonNegLong
import io.estatico.newtype.macros.newtype

object height {

  @derive(arbitrary, encoder, decoder, order, ordering, show, eqv)
  @newtype
  case class Height(value: NonNegLong)

  object Height {
    val MinValue: Height = Height(0L)

    implicit val next: Next[Height] = new Next[Height] {
      def next(a: Height): Height = Height(a.value |+| 1L)
      def partialOrder: PartialOrder[Height] = Order[Height]
    }
  }

  implicit class HeightOps(height: Height) {

    def inRangeInclusive(from: Height, to: Height): Boolean =
      from <= height &&
        height <= to
  }

  @derive(encoder, decoder, eqv, show)
  @newtype
  case class SubHeight(value: NonNegLong)

  object SubHeight {
    val MinValue: SubHeight = SubHeight(NonNegLong.MinValue)

    implicit val next: Next[SubHeight] = new Next[SubHeight] {
      def next(a: SubHeight): SubHeight = SubHeight(a.value |+| 1L)
      def partialOrder: PartialOrder[SubHeight] = Order[Long].contramap(_.value.value)
    }
  }

}
