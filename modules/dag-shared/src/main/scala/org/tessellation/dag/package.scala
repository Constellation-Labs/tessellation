package org.tessellation

import org.tessellation.dag.domain.block.{BlockReference, DAGBlock}
import org.tessellation.dag.snapshot.{GlobalSnapshot, SnapshotOrdinal, StateChannelSnapshotWrapper}

package object dag {

  val dagSharedKryoRegistrar: Map[Class[_], Int] = Map(
    classOf[GlobalSnapshot] -> 500,
    classOf[StateChannelSnapshotWrapper] -> 501,
    classOf[SnapshotOrdinal] -> 502,
    classOf[DAGBlock] -> 503,
    classOf[BlockReference] -> 504
  )
}
