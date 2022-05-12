package org.tessellation.ext.cats.conversion

import cats.Order

object order {

  implicit def orderToOrdering[A: Order]: Ordering[A] = Order[A].toOrdering

}
