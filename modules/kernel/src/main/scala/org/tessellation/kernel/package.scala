package org.tessellation

import org.tessellation.ext.kryo._

import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto._
import eu.timepit.refined.numeric.{GreaterEqual, Interval}

package object kernel {
  type StateChannelKryoRegistrationIdRange = Int Refined GreaterEqual[1000]
  type StateChannelKryoRegistrationId = KryoRegistrationId[StateChannelKryoRegistrationIdRange]

  type KernelKryoRegistrationIdRange = Interval.Closed[400, 499]
  type KernelKryoRegistrationId = KryoRegistrationId[KernelKryoRegistrationIdRange]

  val kernelKryoRegistrar: Map[Class[_], KernelKryoRegistrationId] = Map(
    classOf[ProcessSnapshot] -> 400,
    classOf[StateChannelSnapshotGist] -> 401
  )
}
