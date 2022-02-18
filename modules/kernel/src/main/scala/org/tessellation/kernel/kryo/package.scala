package org.tessellation.kernel

package object kryo {

  val kernelKryoRegistrar: Map[Class[_], Int] = Map(
    classOf[ProcessSnapshot] -> 300
  )

}
