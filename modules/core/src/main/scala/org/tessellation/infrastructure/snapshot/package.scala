package org.tessellation.infrastructure

import org.tessellation.schema._
import org.tessellation.sdk.infrastructure.snapshot.SnapshotConsensus
import org.tessellation.security.signature.Signed
import org.tessellation.statechannel.StateChannelOutput

package object snapshot {

  type DAGEvent = Signed[Block]

  type StateChannelEvent = StateChannelOutput

  type GlobalSnapshotEvent = Either[StateChannelEvent, DAGEvent]

  type GlobalSnapshotKey = SnapshotOrdinal

  type GlobalSnapshotArtifact = GlobalIncrementalSnapshot

  type GlobalSnapshotContext = GlobalSnapshotInfo

  type GlobalSnapshotConsensus[F[_]] =
    SnapshotConsensus[F, GlobalSnapshotArtifact, GlobalSnapshotContext, GlobalSnapshotEvent]

}
