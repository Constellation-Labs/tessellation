package org.tessellation

import org.tessellation.dag.domain.block._
import org.tessellation.dag.snapshot._
import org.tessellation.ext.kryo._
import org.tessellation.statechannel.StateChannelSnapshotBinary

import eu.timepit.refined.auto._
import eu.timepit.refined.numeric.Greater

package object dag {

  type DagSharedKryoRegistrationIdRange = Greater[100]

  type DagSharedKryoRegistrationId = KryoRegistrationId[DagSharedKryoRegistrationIdRange]

  val dagSharedKryoRegistrar: Map[Class[_], DagSharedKryoRegistrationId] = Map(
    classOf[GlobalSnapshot] -> 600,
    classOf[StateChannelSnapshotBinary] -> 601,
    classOf[DAGBlock] -> 603,
    DAGBlock.OrderingInstance.getClass -> 604,
    classOf[GlobalSnapshotInfo] -> 606,
    classOf[DAGBlockAsActiveTip] -> 610,
    DAGBlockAsActiveTip.OrderingInstance.getClass -> 611,
    classOf[Tips] -> 614
  )
}
