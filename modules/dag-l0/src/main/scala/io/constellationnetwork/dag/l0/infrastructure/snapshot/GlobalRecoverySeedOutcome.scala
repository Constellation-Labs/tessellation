package io.constellationnetwork.dag.l0.infrastructure.snapshot

import cats.syntax.eq._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.dag.l0.infrastructure.snapshot.schema.{Finished, GlobalConsensusOutcome}
import io.constellationnetwork.node.shared.infrastructure.consensus.state._
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.EventTrigger
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo}
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed

/** Canonical constructor for the synthetic anchor outcome selected by `CL_GL0_RECOVERY_SEED_COMMITTEE`.
  *
  * The rollback lead and selected validators independently reconstruct this exact value from the validated public anchor and the identical
  * environment committee. No new serialization or hash scheme is introduced: the real signed artifact, context, and existing outcome schema
  * are reused.
  */
object GlobalRecoverySeedOutcome {
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

  /** Recognize the canonical synthetic-root shape.
    *
    * Shape alone is not authority. Production accepts this root only after exact public-anchor, environment-committee, eligibility, and
    * first-round alignment checks have succeeded. Reconstruct through the sole canonical constructor instead of maintaining a second field
    * checklist that can silently miss a newly added outcome field.
    */
  def isCanonicalRoot(outcome: GlobalConsensusOutcome): Boolean = {
    val committee = SortedSet.from(outcome.facilitators.value)

    committee.nonEmpty &&
    outcome === seed(
      outcome.finished.signedMajorityArtifact,
      outcome.finished.context,
      outcome.finished.snapshotHash,
      committee
    )
  }
}
