package io.constellationnetwork.kryo

import scala.reflect.ClassTag

class Migration[A <: AnyRef, B <: AnyRef](mapping: A => B)(implicit A: ClassTag[A], B: ClassTag[B]) {
  def toPair: ((Class[_], Class[_]), A => B) = (A.runtimeClass, B.runtimeClass) -> mapping
}

object Migration {

  def apply[A <: AnyRef, B <: AnyRef](
    mapping: A => B
  )(implicit A: ClassTag[A], B: ClassTag[B]): Migration[AnyRef, AnyRef] =
    new Migration[A, B](mapping).asInstanceOf[Migration[AnyRef, AnyRef]]
}
