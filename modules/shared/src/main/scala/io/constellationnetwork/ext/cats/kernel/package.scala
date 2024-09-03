package io.constellationnetwork.ext.cats

import _root_.cats.kernel.{Next => catsNext, PartialPrevious => catsPartialPrevious}

package object kernel {

  object Next {
    def apply[A](implicit N: catsNext[A]) = N
  }

  object PartialPrevious {
    def apply[A](implicit P: catsPartialPrevious[A]) = P
  }
}
