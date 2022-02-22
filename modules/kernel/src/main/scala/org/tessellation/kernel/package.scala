package org.tessellation

import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.GreaterEqual

package object kernel {
  type StateChannelKryoRegistrationId = Int Refined GreaterEqual[1000]

  val kernelKryoRegistrar: Map[Class[_], Int] = Map(
    classOf[ProcessSnapshot] -> 400
  )
}
