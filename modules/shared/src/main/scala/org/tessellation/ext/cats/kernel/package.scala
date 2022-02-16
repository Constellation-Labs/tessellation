package org.tessellation.ext.cats

import _root_.cats.kernel.{Next => catsNext}

package object kernel {

  object Next {
    def apply[A](implicit N: catsNext[A]) = N
  }
}
