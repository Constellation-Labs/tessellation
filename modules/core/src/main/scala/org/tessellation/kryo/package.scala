package org.tessellation

import org.tessellation.kernel.kryo.kernelKryoRegistrar
import org.tessellation.schema.kryo.schemaKryoRegistrar

package object kryo {

  val coreKryoRegistrar: Map[Class[_], Int] = Map(
    // classOf[T] -> 123
  ) ++ schemaKryoRegistrar ++ kernelKryoRegistrar

}
