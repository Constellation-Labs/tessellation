package org.tessellation.kernel

import scala.reflect.runtime.universe.TypeTag

abstract class Gistable[T <: Ω: TypeTag] extends Ω {
  def gist: T
}
