package org.tessellation.currency.l0

import org.tessellation.currency.l0.snapshot.schema.{CurrencyConsensusKind, CurrencyConsensusOutcome, CurrencyConsensusStep}
import org.tessellation.currency.schema.currency.CurrencySnapshotContext
import org.tessellation.node.shared.infrastructure.consensus._
import org.tessellation.node.shared.infrastructure.snapshot.SnapshotConsensus
import org.tessellation.node.shared.snapshot.currency.{CurrencySnapshotArtifact, CurrencySnapshotEvent}
import org.tessellation.schema.SnapshotOrdinal

package object snapshot {

  type CurrencySnapshotKey = SnapshotOrdinal

  type CurrencySnapshotStatus = CurrencyConsensusStep

  type CurrencySnapshotConsensus[F[_]] =
    SnapshotConsensus[
      F,
      CurrencySnapshotArtifact,
      CurrencySnapshotContext,
      CurrencySnapshotEvent,
      CurrencySnapshotStatus,
      CurrencyConsensusOutcome,
      CurrencyConsensusKind
    ]

  type CurrencySnapshotConsensusState =
    ConsensusState[CurrencySnapshotKey, CurrencySnapshotStatus, CurrencyConsensusOutcome, CurrencyConsensusKind]

  type CurrencyConsensusStorage[F[_]] =
    ConsensusStorage[
      F,
      CurrencySnapshotEvent,
      CurrencySnapshotKey,
      CurrencySnapshotArtifact,
      CurrencySnapshotContext,
      CurrencySnapshotStatus,
      CurrencyConsensusOutcome,
      CurrencyConsensusKind
    ]

  type CurrencyConsensusManager[F[_]] =
    ConsensusManager[
      F,
      CurrencySnapshotKey,
      CurrencySnapshotArtifact,
      CurrencySnapshotContext,
      CurrencySnapshotStatus,
      CurrencyConsensusOutcome,
      CurrencyConsensusKind
    ]

  type CurrencyConsensusStateRemover[F[_]] =
    ConsensusStateRemover[
      F,
      CurrencySnapshotKey,
      CurrencySnapshotEvent,
      CurrencySnapshotArtifact,
      CurrencySnapshotContext,
      CurrencySnapshotStatus,
      CurrencyConsensusOutcome,
      CurrencyConsensusKind
    ]
}
