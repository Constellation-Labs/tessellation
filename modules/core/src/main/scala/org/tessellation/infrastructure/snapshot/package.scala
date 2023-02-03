package org.tessellation.infrastructure

import org.tessellation.dag.domain.block.DAGBlock
import org.tessellation.dag.snapshot._
import org.tessellation.schema.SnapshotOrdinal
import org.tessellation.sdk.infrastructure.consensus.Consensus
import org.tessellation.security.signature.Signed
import org.tessellation.statechannel.StateChannelOutput

package object snapshot {

  type DAGEvent = Signed[DAGBlock]

  type StateChannelEvent = StateChannelOutput

  type GlobalSnapshotEvent = Either[StateChannelEvent, DAGEvent]

  type GlobalSnapshotKey = SnapshotOrdinal

  type GlobalSnapshotArtifact = GlobalSnapshot

  type GlobalSnapshotConsensus[F[_]] = Consensus[F, GlobalSnapshotEvent, GlobalSnapshotKey, GlobalSnapshotArtifact]

}
