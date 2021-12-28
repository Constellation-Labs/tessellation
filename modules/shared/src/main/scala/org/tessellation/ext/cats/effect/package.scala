package org.tessellation.ext.cats

import _root_.cats.effect.{IO, Resource}

package object effect {
  implicit class ResourceIO[A](value: IO[A]) {
    def asResource: Resource[IO, A] = Resource.eval { value }
  }
}
