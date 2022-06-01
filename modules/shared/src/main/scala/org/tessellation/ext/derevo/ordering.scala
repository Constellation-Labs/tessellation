package org.tessellation.ext.derevo

import _root_.cats.Order
import derevo.{Derivation, NewTypeDerivation}

object ordering extends Derivation[Ordering] with NewTypeDerivation[Ordering] {
  def instance[A: Order]: Ordering[A] = Order[A].toOrdering
}
