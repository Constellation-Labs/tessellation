package org.tessellation.infrastructure

import org.tessellation.schema.block.DAGBlock
import org.tessellation.schema.transaction.DAGTransaction
import org.tessellation.schema.{GlobalSnapshot, SnapshotOrdinal}
import org.tessellation.sdk.infrastructure.snapshot.SnapshotConsensus
import org.tessellation.security.signature.Signed
import org.tessellation.statechannel.StateChannelOutput

package object snapshot {

  type DAGEvent = Signed[DAGBlock]

  type StateChannelEvent = StateChannelOutput

  type GlobalSnapshotEvent = Either[StateChannelEvent, DAGEvent]

  type GlobalSnapshotKey = SnapshotOrdinal

  type GlobalSnapshotArtifact = GlobalSnapshot

  type GlobalSnapshotConsensus[F[_]] = SnapshotConsensus[F, DAGTransaction, DAGBlock, GlobalSnapshotArtifact, GlobalSnapshotEvent]

}
