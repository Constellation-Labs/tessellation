package io.constellationnetwork.dag.l0.infrastructure

import io.constellationnetwork.dag.l0.infrastructure.snapshot.event.GlobalSnapshotEvent
import io.constellationnetwork.dag.l0.infrastructure.snapshot.schema.{GlobalConsensusKind, GlobalConsensusOutcome, GlobalConsensusStep}
import io.constellationnetwork.node.shared.infrastructure.consensus._
import io.constellationnetwork.node.shared.infrastructure.snapshot.SnapshotConsensus
import io.constellationnetwork.schema._

package object snapshot {

  type GlobalSnapshotKey = SnapshotOrdinal

  type GlobalSnapshotArtifact = GlobalIncrementalSnapshot

  type GlobalSnapshotContext = GlobalSnapshotInfo

  type GlobalSnapshotStatus = GlobalConsensusStep

  type GlobalSnapshotConsensusState = ConsensusState[GlobalSnapshotKey, GlobalSnapshotStatus, GlobalConsensusOutcome, GlobalConsensusKind]

  type GlobalSnapshotConsensus[F[_]] =
    SnapshotConsensus[
      F,
      GlobalSnapshotArtifact,
      GlobalSnapshotContext,
      GlobalSnapshotEvent,
      GlobalSnapshotStatus,
      GlobalConsensusOutcome,
      GlobalConsensusKind
    ]

  type GlobalConsensusStorage[F[_]] =
    ConsensusStorage[
      F,
      GlobalSnapshotEvent,
      GlobalSnapshotKey,
      GlobalSnapshotArtifact,
      GlobalSnapshotContext,
      GlobalSnapshotStatus,
      GlobalConsensusOutcome,
      GlobalConsensusKind
    ]

  type GlobalConsensusManager[F[_]] =
    ConsensusManager[
      F,
      GlobalSnapshotKey,
      GlobalSnapshotArtifact,
      GlobalSnapshotContext,
      GlobalSnapshotStatus,
      GlobalConsensusOutcome,
      GlobalConsensusKind
    ]

  type GlobalConsensusStateRemover[F[_]] =
    ConsensusStateRemover[
      F,
      GlobalSnapshotKey,
      GlobalSnapshotEvent,
      GlobalSnapshotArtifact,
      GlobalSnapshotContext,
      GlobalSnapshotStatus,
      GlobalConsensusOutcome,
      GlobalConsensusKind
    ]
}
