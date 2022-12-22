package org.tessellation

import cats.data.NonEmptyList

import org.tessellation.ext.kryo._

import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto._
import eu.timepit.refined.numeric.GreaterEqual

package object shared {

  type SharedKryoRegistrationIdRange = GreaterEqual[300]

  type SharedKryoRegistrationId = KryoRegistrationId[SharedKryoRegistrationIdRange]

  val sharedKryoRegistrar: Map[Class[_], SharedKryoRegistrationId] = Map(
    classOf[NonEmptyList[_]] -> 305,
    classOf[Refined[_, _]] -> 332
  )

}
