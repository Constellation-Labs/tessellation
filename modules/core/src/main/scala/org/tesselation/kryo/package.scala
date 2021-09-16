package org.tesselation

import org.tesselation.schema.kryo.schemaKryoRegistrar

package object kryo {

  val coreKryoRegistrar: Map[Class[_], Int] = Map(
    // classOf[T] -> 123
  ) ++ schemaKryoRegistrar

}
