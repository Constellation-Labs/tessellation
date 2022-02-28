package org.tessellation.infrastructure

import cats.syntax.either._

import org.tessellation.dag.domain.block.DAGBlock
import org.tessellation.dag.snapshot._
import org.tessellation.domain.aci.StateChannelGistedOutput
import org.tessellation.domain.snapshot.SnapshotTrigger
import org.tessellation.kernel.StateChannelSnapshot
import org.tessellation.security.signature.Signed

import eu.timepit.refined.auto._

package object snapshot {

  type DAGEvent = Either[Signed[DAGBlock], SnapshotTrigger]

  type StateChannelEvent = StateChannelGistedOutput[StateChannelSnapshot]

  type GlobalSnapshotEvent = Either[StateChannelEvent, DAGEvent]

  type GlobalSnapshotKey = SnapshotOrdinal

  type GlobalSnapshotArtifact = GlobalSnapshot

  def toEvent(trigger: SnapshotTrigger): GlobalSnapshotEvent =
    trigger.asRight[Signed[DAGBlock]].asRight[StateChannelEvent]

}
