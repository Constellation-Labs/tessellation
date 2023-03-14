package org.tessellation

import org.tessellation.currency.schema.currency._
import org.tessellation.ext.kryo.KryoRegistrationId

import eu.timepit.refined.auto._
import eu.timepit.refined.numeric.Interval

package object currency {

  type CurrencyKryoRegistrationIdRange = Interval.Closed[900, 999]
  type CurrencyKryoRegistrationId = KryoRegistrationId[CurrencyKryoRegistrationIdRange]

  val currencyKryoRegistrar: Map[Class[_], CurrencyKryoRegistrationId] = Map(
    classOf[CurrencyTransaction] -> 900,
    classOf[CurrencyBlock] -> 901,
    classOf[CurrencySnapshotInfo] -> 902,
    classOf[CurrencySnapshot] -> 903,
    CurrencyBlock.OrderingInstanceAsActiveTip.getClass -> 904,
    CurrencyTransaction.OrderingInstance.getClass -> 905,
    CurrencyBlock.OrderingInstance.getClass -> 906
  )
}
