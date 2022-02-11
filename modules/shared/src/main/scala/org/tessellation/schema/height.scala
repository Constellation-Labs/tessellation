package org.tessellation.schema

import cats.kernel.Next
import cats.syntax.semigroup._
import cats.{Order, PartialOrder}

import org.tessellation.ext.derevo.ordering

import derevo.cats.{order, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import eu.timepit.refined.cats.refTypeShow
import eu.timepit.refined.types.numeric.NonNegLong
import io.estatico.newtype.macros.newtype

object height {

  @derive(encoder, decoder, order, ordering, show)
  @newtype
  case class Height(value: Long)

  object Height {
    val MinValue: Height = Height(0)

    implicit val next: Next[Height] = new Next[Height] {
      def next(a: Height): Height = Height(a.value |+| 1)
      def partialOrder: PartialOrder[Height] = Order[Height]
    }
  }

  @derive(show)
  case class SubHeight(value: NonNegLong)

  object SubHeight {
    val MinValue: SubHeight = SubHeight(NonNegLong.MinValue)
  }

}
