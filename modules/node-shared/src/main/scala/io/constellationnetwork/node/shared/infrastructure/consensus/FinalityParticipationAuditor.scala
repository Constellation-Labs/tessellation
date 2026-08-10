package io.constellationnetwork.node.shared.infrastructure.consensus

import cats.Order

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
    consecutiveMisses: Map[PeerId, Int]
  )

  object MissHistory {
    val empty: MissHistory = MissHistory(None, Map.empty)
  }

  final case class Decision(
    target: PeerId,
    signatureObserved: Boolean,
    consecutiveMisses: Int,
    requiredConsecutiveMisses: Int
  ) {
    def shouldVote: Boolean = !signatureObserved && consecutiveMisses >= requiredConsecutiveMisses
  }

  final case class Observation(history: MissHistory, decision: Option[Decision])

  /** Combine the three-round proof-miss hysteresis with the exact next-seat finality-headroom invariant.
    *
    * This remains a local vote-emission decision. A Core quorum of existing EvictionVotes is still required before membership changes.
    */
  def shouldEmitSilentEvictionVote(decision: Decision, headroom: FinalityHeadroom.Evaluation): Boolean =
    decision.shouldVote && headroom.allowsSilentEviction

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
    inBootstrap: Boolean,
    previous: MissHistory,
    requiredConsecutiveMisses: Int = TierTransitions.DemotionConsecutiveMisses
  ): Observation = {
    val required = math.max(1, requiredConsecutiveMisses)

    if (inBootstrap) Observation(MissHistory.empty, None)
    else {
      val auditable = currentTier1.intersect(parentRoundCommittee)
      val observedParent = ObservedParent(parentOrdinal, entropy)
      val nextMisses =
        if (previous.lastObservedParent.contains(observedParent))
          auditable.iterator.map(pid => pid -> previous.consecutiveMisses.getOrElse(pid, 0)).toMap
        else {
          val previousConsecutiveMisses = previous.lastObservedParent match {
            case Some(parent) if parent.ordinal < Long.MaxValue && parent.ordinal + 1L == parentOrdinal =>
              previous.consecutiveMisses
            case _ => Map.empty[PeerId, Int]
          }
          auditable.iterator.map { pid =>
            val misses =
              if (locallyObservedParentSigners.contains(pid)) 0
              else math.min(required, previousConsecutiveMisses.getOrElse(pid, 0) + 1)
            pid -> misses
          }.toMap
        }
      val nextHistory = MissHistory(Some(observedParent), nextMisses)
      val decision = Option
        .when(currentCore.contains(selfId)) {
          selectTarget(currentTier1, parentRoundCommittee, entropy)
        }
        .flatten
        .map { target =>
          Decision(
            target = target,
            signatureObserved = locallyObservedParentSigners.contains(target),
            consecutiveMisses = nextMisses.getOrElse(target, 0),
            requiredConsecutiveMisses = required
          )
        }

      Observation(nextHistory, decision)
    }
  }
}
