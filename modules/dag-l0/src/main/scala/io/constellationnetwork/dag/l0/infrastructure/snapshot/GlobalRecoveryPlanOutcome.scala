package io.constellationnetwork.dag.l0.infrastructure.snapshot

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.dag.l0.infrastructure.snapshot.schema.{Finished, GlobalConsensusOutcome}
import io.constellationnetwork.node.shared.infrastructure.consensus.state._
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.EventTrigger
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo, SnapshotOrdinal}
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed

/** One canonical constructor for the exact synthetic anchor outcome authorized by a GL0 recovery plan.
  *
  * Keeping this in one typed function prevents the rollback lead and planned validators from drifting on which operational windows are
  * flushed. It introduces no serialization or hashing scheme; the real signed artifact/context/hash and existing outcome schema are reused.
  */
object GlobalRecoveryPlanOutcome {
  def seed(
    snapshot: Signed[GlobalIncrementalSnapshot],
    snapshotInfo: GlobalSnapshotInfo,
    snapshotHash: Hash,
    committee: SortedSet[PeerId]
  ): GlobalConsensusOutcome =
    GlobalConsensusOutcome(
      snapshot.ordinal,
      Facilitators(committee.toList),
      RemovedFacilitators.empty,
      WithdrawnFacilitators.empty,
      EligibleFacilitators(committee.toList),
      Finished(snapshot, snapshotInfo, EventTrigger, Candidates.empty, Hash.empty, snapshotHash),
      recentProofSizes = SortedMap(snapshot.ordinal -> committee.size)
    )
}
