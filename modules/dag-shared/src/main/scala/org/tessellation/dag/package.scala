package org.tessellation

import org.tessellation.ext.kryo._
import org.tessellation.schema.block.{DAGBlock, Tips}
import org.tessellation.schema.{BlockAsActiveTip, GlobalSnapshot, GlobalSnapshotInfo}
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
    classOf[BlockAsActiveTip[_]] -> 610,
    DAGBlock.OrderingInstanceAsActiveTip.getClass -> 611,
    classOf[Tips] -> 614,
    classOf[IncrementalGlobalSnapshot] -> 615
  )
}
