package org.tessellation

import org.tessellation.dag.domain.block._
import org.tessellation.dag.snapshot._
import org.tessellation.schema.kryo.ProtocolKryoRegistrationId
import org.tessellation.schema.statechannels.StateChannelSnapshotBinary

import eu.timepit.refined.auto._

package object dag {

  val dagSharedKryoRegistrar: Map[Class[_], ProtocolKryoRegistrationId] = Map(
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
