package org.tessellation

import org.tessellation.dag.domain.block._
import org.tessellation.dag.snapshot._
import org.tessellation.ext.kryo._

import eu.timepit.refined.auto._
import eu.timepit.refined.numeric.Interval

package object dag {

  type DagSharedKryoRegistrationIdRange = Interval.Closed[600, 699]

  type DagSharedKryoRegistrationId = KryoRegistrationId[DagSharedKryoRegistrationIdRange]

  val dagSharedKryoRegistrar: Map[Class[_], DagSharedKryoRegistrationId] = Map(
    classOf[GlobalSnapshot] -> 600,
    classOf[StateChannelSnapshotBinary] -> 601,
    classOf[SnapshotOrdinal] -> 602,
    classOf[DAGBlock] -> 603,
    DAGBlock.OrderingInstance.getClass -> 604,
    classOf[BlockReference] -> 605,
    classOf[GlobalSnapshotInfo] -> 606,
    classOf[GlobalSnapshotTips] -> 607,
    classOf[ActiveTip] -> 608,
    ActiveTip.OrderingInstance.getClass -> 609,
    classOf[BlockAsActiveTip] -> 610,
    BlockAsActiveTip.OrderingInstance.getClass -> 611,
    classOf[DeprecatedTip] -> 612,
    DeprecatedTip.OrderingInstance.getClass -> 613,
    classOf[Tips] -> 614
  )
}
