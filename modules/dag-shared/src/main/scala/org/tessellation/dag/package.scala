package org.tessellation

import org.tessellation.dag.domain.block.{BlockReference, DAGBlock}
import org.tessellation.dag.snapshot.{GlobalSnapshot, SnapshotOrdinal, StateChannelSnapshotWrapper}

package object dag {

  val dagSharedKryoRegistrar: Map[Class[_], Int] = Map(
    classOf[GlobalSnapshot] -> 600,
    classOf[StateChannelSnapshotWrapper] -> 601,
    classOf[SnapshotOrdinal] -> 602,
    classOf[DAGBlock] -> 603,
    classOf[BlockReference] -> 604
  )
}
