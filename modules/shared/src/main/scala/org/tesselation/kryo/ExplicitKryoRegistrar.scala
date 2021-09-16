package org.tesselation.kryo

import com.esotericsoftware.kryo.serializers.CompatibleFieldSerializer
import com.twitter.chill.{IKryoRegistrar, Kryo, ObjectSerializer}

case class ExplicitKryoRegistrar(registrar: Map[Class[_], Int]) extends IKryoRegistrar {

  val objectSerializer = new ObjectSerializer[AnyRef]

  def apply(k: Kryo): Unit =
    registrar.toList.foreach {
      case (klass, id) =>
        val ser = if (isScalaObject(klass)) {
          objectSerializer
        } else {
          val fs = new CompatibleFieldSerializer(k, klass)
          fs.setIgnoreSyntheticFields(false)
          fs
        }
        k.register(klass, ser, id)
        ()
    }

  def isScalaObject(klass: Class[_]): Boolean = klass.getName.last == '$' && objectSerializer.accepts(klass)
}
