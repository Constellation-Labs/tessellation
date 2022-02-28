package org.tessellation

import org.tessellation.dag.domain.block.{BlockReference, DAGBlock}
import org.tessellation.dag.snapshot._

package object dag {

  val dagSharedKryoRegistrar: Map[Class[_], Int] = Map(
    classOf[GlobalSnapshot] -> 600,
    classOf[StateChannelSnapshotBinary] -> 601,
    classOf[SnapshotOrdinal] -> 602,
    classOf[DAGBlock] -> 603,
    classOf[BlockReference] -> 604,
    classOf[GlobalSnapshotInfo] -> 605
  )
}
