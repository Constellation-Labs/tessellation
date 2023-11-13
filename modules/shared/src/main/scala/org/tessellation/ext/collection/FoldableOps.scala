package org.tessellation.ext.collection

import cats.syntax.foldable._
import cats.{Foldable, Order}

object FoldableOps {
  def pickMajority[F[_]: Foldable, A: Order](fa: F[A]): Option[A] =
    fa.foldMap(a => Map(a -> 1)).toList.map(_.swap).maximumOption.map(_._2)
}
