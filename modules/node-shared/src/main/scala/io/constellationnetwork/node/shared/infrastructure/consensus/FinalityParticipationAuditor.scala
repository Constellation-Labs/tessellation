package io.constellationnetwork.node.shared.infrastructure.consensus

import cats.Order

import scala.concurrent.duration.{Duration, FiniteDuration}

import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash

/** Bounded, deterministic audit of Tier-1 snapshot-signature participation.
  *
  * The finalized artifact's proof set is intentionally node-local: honest nodes can cross the finality threshold with different valid
  * signature subsets. It must therefore never be copied directly into committee state. This helper uses that local observation only to
  * decide whether the local Core member emits an existing `EvictionVote(Silent)`. Membership changes only after the normal quorum-certified
  * EvictionCertificate is accepted in a Proposal.
  *
  * One peer is audited per round. The auditable universe is consensus-agreed -- current Tier 1 intersected with the parent round's
  * canonical committee -- and rendezvous-ranked from the parent snapshot hash. Intersecting with the parent committee prevents a newly
  * admitted Tier-1 peer, which could not have signed the parent, from being immediately treated as missing.
  */
object FinalityParticipationAuditor {

  final case class ObservedParent(ordinal: Long, hash: Hash)

  final case class MissHistory(
    lastObservedParent: Option[ObservedParent],
    consecutiveMisses: Map[PeerId, Int],
    missStartedAt: Map[PeerId, FiniteDuration]
  )

  object MissHistory {
    val empty: MissHistory = MissHistory(None, Map.empty, Map.empty)
  }

  final case class Decision(
    target: PeerId,
    signatureObserved: Boolean,
    consecutiveMisses: Int,
    requiredConsecutiveMisses: Int,
    consecutiveMissDuration: FiniteDuration,
    requiredMissDuration: FiniteDuration
  ) {
    def roundHysteresisSatisfied: Boolean = consecutiveMisses >= requiredConsecutiveMisses
    def durationHysteresisSatisfied: Boolean = consecutiveMissDuration >= requiredMissDuration
    def shouldVote: Boolean = !signatureObserved && roundHysteresisSatisfied && durationHysteresisSatisfied
  }

  final case class Observation(history: MissHistory, decision: Option[Decision])

  /** Combine proof-miss hysteresis, the exact current-seat finality deficit, and the existing membership-change cadence.
    *
    * This remains a local vote-emission decision. A Core quorum of existing EvictionVotes is still required before membership changes.
    */
  def shouldEmitSilentEvictionVote(
    decision: Decision,
    headroom: FinalityHeadroom.Evaluation,
    cadenceAllowed: Boolean
  ): Boolean =
    decision.shouldVote && headroom.allowsSilentEviction && cadenceAllowed

  /** Preserve the intended elapsed-time meaning of an N-round miss window when EventTrigger accelerates rounds.
    *
    * N observations span N-1 intervals from the first miss through the Nth miss. Reusing `timeTriggerInterval` gives the local auditor a
    * stable lower bound without adding configuration. This is local abstention policy only; differing clocks or timing configuration cannot
    * mutate membership without the existing Core-quorum certificate.
    */
  def minimumMissDuration(timeTriggerInterval: FiniteDuration, requiredConsecutiveMisses: Int): FiniteDuration = {
    val intervals = math.max(0, requiredConsecutiveMisses - 1).toLong
    if (timeTriggerInterval <= Duration.Zero) Duration.Zero
    else timeTriggerInterval * intervals
  }

  def selectTarget(
    currentTier1: Set[PeerId],
    parentRoundCommittee: Set[PeerId],
    entropy: Hash
  ): Option[PeerId] = {
    implicit val scoreOrder: Order[PeerId] = FacilitatorSelector.orderByScore(entropy)

    currentTier1
      .intersect(parentRoundCommittee)
      .toList
      .sorted(scoreOrder.toOrdering)
      .headOption
  }

  /** Advance local proof-miss history once for the finalized parent and evaluate this round's consensus-agreed audit target.
    *
    * Every auditable Tier-1 peer is updated on every new parent: a locally observed signature resets its streak to zero, while absence
    * increments it. The target is still selected from the complete consensus-agreed auditable universe, never from local miss-qualified
    * peers, so differing valid proof subsets can only make a Core voter abstain rather than vote for a different target. Re-observing the
    * same `(ordinal, hash)` is idempotent; a non-consecutive ordinal starts a fresh sequence.
    */
  def observe(
    selfId: PeerId,
    currentCore: Set[PeerId],
    currentTier1: Set[PeerId],
    parentRoundCommittee: Set[PeerId],
    locallyObservedParentSigners: Set[PeerId],
    parentOrdinal: Long,
    entropy: Hash,
    observedAt: FiniteDuration,
    requiredMissDuration: FiniteDuration,
    inBootstrap: Boolean,
    previous: MissHistory,
    requiredConsecutiveMisses: Int = TierTransitions.DemotionConsecutiveMisses
  ): Observation = {
    val required = math.max(1, requiredConsecutiveMisses)

    if (inBootstrap) Observation(MissHistory.empty, None)
    else {
      val auditable = currentTier1.intersect(parentRoundCommittee)
      val observedParent = ObservedParent(parentOrdinal, entropy)
      val (nextMisses, nextMissStartedAt) =
        if (previous.lastObservedParent.contains(observedParent))
          (
            auditable.iterator.map(pid => pid -> previous.consecutiveMisses.getOrElse(pid, 0)).toMap,
            previous.missStartedAt.view.filterKeys(auditable.contains).toMap
          )
        else {
          val isConsecutive = previous.lastObservedParent.exists { parent =>
            parent.ordinal < Long.MaxValue && parent.ordinal + 1L == parentOrdinal
          }
          val previousConsecutiveMisses =
            if (isConsecutive) previous.consecutiveMisses else Map.empty[PeerId, Int]
          val previousMissStartedAt =
            if (isConsecutive) previous.missStartedAt else Map.empty[PeerId, FiniteDuration]
          val misses = auditable.iterator.map { pid =>
            val count =
              if (locallyObservedParentSigners.contains(pid)) 0
              else math.min(required, previousConsecutiveMisses.getOrElse(pid, 0) + 1)
            pid -> count
          }.toMap
          val startedAt = auditable.iterator.flatMap { pid =>
            if (locallyObservedParentSigners.contains(pid)) None
            else Some(pid -> previousMissStartedAt.getOrElse(pid, observedAt))
          }.toMap

          (misses, startedAt)
        }
      val nextHistory = MissHistory(Some(observedParent), nextMisses, nextMissStartedAt)
      val decision = Option
        .when(currentCore.contains(selfId)) {
          selectTarget(currentTier1, parentRoundCommittee, entropy)
        }
        .flatten
        .map { target =>
          val missDuration = nextMissStartedAt
            .get(target)
            .fold(Duration.Zero: FiniteDuration)(startedAt => (observedAt - startedAt).max(Duration.Zero))

          Decision(
            target = target,
            signatureObserved = locallyObservedParentSigners.contains(target),
            consecutiveMisses = nextMisses.getOrElse(target, 0),
            requiredConsecutiveMisses = required,
            consecutiveMissDuration = missDuration,
            requiredMissDuration = requiredMissDuration.max(Duration.Zero)
          )
        }

      Observation(nextHistory, decision)
    }
  }
}
