package org.tesselation.schema

import org.tesselation.schema.address.AddressCache
import org.tesselation.schema.peer.SignRequest

package object kryo {

  val schemaKryoRegistrar: Map[Class[_], Int] = Map(
    classOf[AddressCache] -> 101,
    classOf[SignRequest] -> 102
  )
}
