package org.tessellation.serialization

import com.esotericsoftware.kryo.Kryo
import com.twitter.chill.IKryoRegistrar
import org.tessellation.SerializationExample.Lorem

class KryoRegistrar extends IKryoRegistrar {
  override def apply(kryo: Kryo): Unit = {
    registerClasses(kryo)
  }

  private def registerClasses(kryo: Kryo): Unit = {
    kryo.register(classOf[Lorem])
  }
}
