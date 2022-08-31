package org.tessellation.dag.l1.rosetta.model.dag

import eu.timepit.refined.numeric.Interval
import org.tessellation.ext.kryo.KryoRegistrationId
import org.tessellation.schema.transaction.RewardTransaction

object Registry {

  import eu.timepit.refined.auto._

  type RunnerKryoRegistrationIdRange = Interval.Closed[300, 399]

  type RunnerKryoRegistrationId = KryoRegistrationId[RunnerKryoRegistrationIdRange]

  val rosettaKryoRegistrar: Map[Class[_], RunnerKryoRegistrationId] = Map(
    classOf[RewardTransaction] -> 389,
//    classOf[Signed[Transaction]] -> 390,
//    SignatureProof.OrderingInstance.getClass -> 391
  )

}
