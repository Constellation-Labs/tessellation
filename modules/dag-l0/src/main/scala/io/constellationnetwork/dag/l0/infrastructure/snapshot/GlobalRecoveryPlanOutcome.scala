package io.constellationnetwork.dag.l0.infrastructure.snapshot

import cats.syntax.eq._

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

  /** Recognize the locally persisted, independently authorized root shape.
    *
    * Presence in the certified-outcome sidecar is the local provenance boundary: production writes an uncertified outcome there only for an
    * already verified recovery-plan anchor (or certified-consensus genesis). This structural check prevents an arbitrary legacy outcome
    * from being promoted to a v35 root. It does not confer authority on peer-supplied bytes.
    */
  def isCanonicalRoot(outcome: GlobalConsensusOutcome): Boolean = {
    val committee = outcome.facilitators.value
    val expectedProofWindow = SortedMap(outcome.key -> committee.size)

    committee.nonEmpty &&
    outcome.eligibleFacilitators.value == committee &&
    outcome.removedFacilitators.value.isEmpty &&
    outcome.withdrawnFacilitators.value.isEmpty &&
    outcome.finished.certifiedOutcome.isEmpty &&
    outcome.finished.candidates.value.isEmpty &&
    outcome.finished.facilitatorsHash === Hash.empty &&
    outcome.removalPenalties.isEmpty &&
    outcome.deferralCountdown.isEmpty &&
    outcome.peerQuality.isEmpty &&
    outcome.cumulativeMissCounts.isEmpty &&
    outcome.recentProofSizes === expectedProofWindow &&
    outcome.readmissionCountdown.isEmpty &&
    outcome.peerSelfHealth.isEmpty &&
    outcome.peerViewChanges.isEmpty &&
    outcome.recentSigners.isEmpty &&
    outcome.peerTiers.isEmpty &&
    outcome.activeAdmissionScores.isEmpty &&
    outcome.lastTimeoutCertificateVoters.isEmpty &&
    outcome.recentRoundEndTimes.isEmpty &&
    outcome.controllerEvidence.isEmpty &&
    outcome.penaltyUntil.isEmpty
  }
}
