package org.tesselation.schema

import org.tesselation.schema.address.AddressCache

package object kryo {

  val schemaKryoRegistrar: Map[Class[_], Int] = Map(
    classOf[AddressCache] -> 101
  )
}
