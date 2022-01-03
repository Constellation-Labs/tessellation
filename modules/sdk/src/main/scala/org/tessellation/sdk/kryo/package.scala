package org.tessellation.sdk

import org.tessellation.kernel.kryo.kernelKryoRegistrar
import org.tessellation.schema.kryo.schemaKryoRegistrar

package object kryo {

  val sdkKryoRegistrar: Map[Class[_], Int] = Map(
    // classOf[T] -> 123
  ) ++ schemaKryoRegistrar ++ kernelKryoRegistrar

}
