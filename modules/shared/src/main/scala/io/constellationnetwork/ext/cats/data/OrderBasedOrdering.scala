package io.constellationnetwork.ext.cats.data

import _root_.cats.Order

abstract class OrderBasedOrdering[A: Order] extends Ordering[A] {
  override def compare(x: A, y: A): Int = Order[A].compare(x, y)
}
