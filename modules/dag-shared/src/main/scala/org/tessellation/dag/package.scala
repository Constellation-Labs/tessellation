package org.tessellation

import org.tessellation.dag.domain.block.{BlockReference, DAGBlock}
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
    classOf[BlockReference] -> 604,
    classOf[GlobalSnapshotInfo] -> 605
  )
}
