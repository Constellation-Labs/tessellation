package org.tessellation.aci

import cats.effect.Sync
import com.twitter.chill.{KryoInstantiator, KryoPool, ScalaKryoInstantiator}

object KryoFactory {

  private val kryoInstantiator: KryoInstantiator = new ScalaKryoInstantiator()
    .setRegistrationRequired(true)
    .setReferences(false)

  def createKryoInstance(registrar: Map[Class[_], Int]): KryoPool =
    KryoPool.withByteArrayOutputStream(
      10,
      kryoInstantiator.withRegistrar(ExplicitKryoRegistrar(registrar.toSet))
    )

}
