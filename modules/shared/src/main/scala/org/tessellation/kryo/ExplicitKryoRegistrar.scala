package org.tessellation.kryo

import com.esotericsoftware.kryo.Registration
import com.esotericsoftware.kryo.serializers.CompatibleFieldSerializer
import com.twitter.chill.{IKryoRegistrar, Kryo, ObjectSerializer}

case class ExplicitKryoRegistrar(registrar: Map[Class[_], Int]) extends IKryoRegistrar {

  private val objectSerializer = new ObjectSerializer[AnyRef]

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

        val resolver = k.getClassResolver

        throwIfRegistrationExists(klass, id, resolver.getRegistration(id))
        throwIfRegistrationExists(klass, id, resolver.getRegistration(klass))

        k.register(klass, ser, id)
        ()
    }

  def isScalaObject(klass: Class[_]): Boolean =
    klass.getName.last == '$' && objectSerializer.accepts(klass)

  def throwIfRegistrationExists(klass: Class[_], id: Int, registrationOrNull: Registration): Unit =
    if (registrationOrNull != null)
      throw new Throwable(
        s"Attempt to create registration ${klass.getName}->$id when there's an existing" +
          s" registration ${registrationOrNull.getType.getName}->${registrationOrNull.getId}"
      )

}
