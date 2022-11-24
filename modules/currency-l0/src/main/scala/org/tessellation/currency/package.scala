package org.tessellation

import eu.timepit.refined.numeric.Positive
import org.tessellation.ext.kryo.KryoRegistrationId

package object currency {

  type CurrencyKryoRegistrationIdRange = Positive
  type CurrencyKryoRegistrationId = KryoRegistrationId[CurrencyKryoRegistrationIdRange]

  val currencyKryoRegistrar: Map[Class[_], CurrencyKryoRegistrationId] = Map()
}
