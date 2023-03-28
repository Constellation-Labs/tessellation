package org.tessellation.ext.cats

import _root_.cats.effect.Resource

package object effect {
  implicit class ResourceF[F[_], A](value: F[A]) {
    def asResource: Resource[F, A] = Resource.eval(value)
  }
}
