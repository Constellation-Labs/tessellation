package org.tessellation

import org.tessellation.ext.kryo.KryoRegistrationId

import eu.timepit.refined.numeric.Positive

package object currency {

  type CurrencyKryoRegistrationIdRange = Positive
  type CurrencyKryoRegistrationId = KryoRegistrationId[CurrencyKryoRegistrationIdRange]

  val currencyKryoRegistrar: Map[Class[_], CurrencyKryoRegistrationId] = Map()
}
