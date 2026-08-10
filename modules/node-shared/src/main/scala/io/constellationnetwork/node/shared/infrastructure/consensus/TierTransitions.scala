package io.constellationnetwork.node.shared.infrastructure.consensus

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.peer.PeerId

/** Pure tier-transition function for the v19 multi-committee architecture.
  *
  * ==Three deterministic tiers==
  *
  *   - '''Tier 2 (Core)''': full facilitator, in the LIVENESS quorum. Quorum threshold is computed against `coreFacilitators.value.size`
  *     only; Tier 1 and Tier 0 peers do NOT count toward the active quorum denominator.
  *   - '''Tier 1''': witness-eligible (B1/B2/VCC witness pool), not in the LIVENESS quorum. Demoted to Tier 1 when a Tier 2 peer was in
  *     `roundStartFacilitators[N]` of a SUCCESSFUL round but was NOT in `recentSigners[N]`.
  *   - '''Tier 0 (Witness)''': open membership, observation only. Peers fall to Tier 0 only via explicit eviction outside this transition
  *     function.
  *
  * ==Determinism contract==
  *
  * Every input to `computeNextTier` is a consensus-agreed signed outcome field; every honest node passing the same arguments produces the
  * byte-identical result. No wall-clock, no randomness, no node-local state. Pure function for golden-path test coverage.
  *
  * ==Demotion is gated on round completion==
  *
  * Failed rounds do NOT cascade demote: a Tier 2 peer who was in `roundStartFacilitators` of a round that failed (no `recentSigners[N]`
  * entry exists) keeps Tier 2. Only completed rounds with a witnessed signer set update tier. This closes the failure-mode where a single
  * network flap during a round that would have failed anyway would have collapsed the Core committee.
  *
  * ==Bootstrap default: round-completion vs committee derivation==
  *
  * `computeNextTier` returns `2` (Tier 2 Core) for peers with `priorTier = None`. This default is scoped to ROUND-COMPLETION outcomes: a
  * peer appearing in `roundStartFacilitators[N]` but missing from `priorTiers` must have been promoted into Core by the floor mechanism for
  * round N, so the round-completion outcome treats them as Core (and demotes them to Tier 1 iff they were in roundStart but did NOT sign).
  *
  * `CommitteeBuilder` no longer mirrors this default at COMMITTEE-DERIVATION time. As of the structural fix, an unknown peer at derivation
  * time defaults to Tier 1 unless `peerQuality` proves they belong in Core. The two defaults are intentionally asymmetric: derivation gates
  * Core entry on demonstrated participation; round-completion gates demotion on demonstrated absence.
  */
object TierTransitions {

  /** Tier 2 (Core): in the LIVENESS quorum. */
  val Core: Int = 2

  /** Tier 1: witness-eligible, not in the LIVENESS quorum. */
  val Tier1: Int = 1

  /** Tier 0 (Witness): observation only. */
  val Witness: Int = 0

  /** Sustained-silence demotion threshold: a Core peer is demoted to Tier 1 only after it is absent from this many of the MOST RECENT
    * completed-round signer sets (`recentSigners` window). The window includes the just-completed round, so a peer that signs the current
    * round is in the latest entry and is NEVER demoted.
    *
    * ==Why a window rather than the prior single-round rule==
    *
    * The prior rule demoted on a SINGLE missed signature. That was tolerable only because the Core floor immediately re-promoted the peer
    * the next round (demote-then-repromote no-op on a small cluster). Once the floor is lowered so a demotion can stick, a single slow
    * round (a brief GC pause, a network blip on a peer that signs in ~1.4s normally) would permanently shed a healthy peer. Requiring
    * absence across `DemotionConsecutiveMisses` recent signer sets sheds only a peer that has STOPPED signing, not one that is merely
    * occasionally slow. The demoted peer remains Tier 1 (witness-eligible, still earns) and is re-promoted by `CommitteeBuilder` once its
    * quality recovers.
    *
    * The `priorTier == Core` gate in `computeNextTier` already means a peer CARRIED as Tier 1 (e.g. just re-promoted into the round by the
    * CommitteeBuilder floor) is never demoted by this rule, which covers the common Tier1 -> Core re-promotion case.
    *
    * ==Known limitation: not fully eligibility-aware (accepted for crash-faulty testnet)==
    *
    * The window holds SIGNER sets, not per-round eligibility (roundStart membership). The rule does NOT verify the peer was in
    * `roundStartFacilitators` for each of the recent rounds it is judged absent from -- we do not persist a windowed roundStart history. So
    * the guarantee is "absent from the last N signer sets", which is WEAKER than "missed the last N rounds it was eligible to sign". The
    * gap that can fire: a peer whose carried tier is still Core but which was absent from `roundStartFacilitators` for the prior rounds (a
    * transient unresponsiveness or a mid-round withdrawal that did not change its tier) then re-enters roundStart and misses once -- it can
    * be demoted as if it missed N eligible rounds when it really missed one. Consequence is bounded and recoverable (Tier 1, re-promoted by
    * quality), and on a small crash-faulty cluster the gap is narrow, so this is accepted for now. A fully eligibility-aware version would
    * additionally persist a windowed roundStart-membership history and count a miss only for rounds the peer was actually in roundStart;
    * see the alpha-deploy handoff for that follow-up.
    *
    * Compiled-in constant, not config: gated by the jar hash (every node runs the same value), so it needs no slot in
    * `deterministicConfigHash`. Promote to config later if runtime tuning is wanted.
    */
  val DemotionConsecutiveMisses: Int = 3

  /** Compute the next tier for a single peer given the prior tier and round outcome.
    *
    * @param priorTier
    *   The peer's tier carried from the prior outcome (`lastOutcome.peerTiers`). `None` means the peer has no classification yet --
    *   bootstrap default, treated as Tier 2.
    * @param wasInRoundStart
    *   Was the peer in `roundStartFacilitators[N]`? Only Tier 2 peers (or bootstrap-default peers) can be in `roundStartFacilitators`, so
    *   this is effectively a "did this peer participate as Core this round" signal.
    * @param missedRecentConsecutive
    *   Was the peer absent from ALL of the most-recent `DemotionConsecutiveMisses` signer sets in the window (and is the window deep enough
    *   to judge)? See `DemotionConsecutiveMisses`.
    * @param roundCompleted
    *   Did this round produce a `Finished` outcome with a signed artifact? Failed rounds do not cascade-demote (return the prior tier
    *   unchanged).
    * @return
    *   The peer's tier going into round N+1.
    */
  def computeNextTier(
    priorTier: Option[Int],
    wasInRoundStart: Boolean,
    missedRecentConsecutive: Boolean,
    roundCompleted: Boolean
  ): Int = {
    val current = priorTier.getOrElse(Core)
    if (!roundCompleted) current
    else if (current == Core && wasInRoundStart && missedRecentConsecutive) Tier1
    else current
  }

  /** Apply `computeNextTier` to every peer that participated in the round, producing the next round's `peerTiers` map.
    *
    * @param priorTiers
    *   `lastOutcome.peerTiers` -- the carried-forward classification from the prior round.
    * @param roundStartFacilitators
    *   The canonical round-start committee. Demotion gates on membership here so a peer who was withdrawn mid-round (and never expected to
    *   sign) is not penalized.
    * @param recentSignersWindow
    *   The rolling per-ordinal signer-set window, INCLUDING the just-completed round (`recentSigners` on the outcome). Consensus-agreed and
    *   fully sorted (`SortedMap[SnapshotOrdinal, SortedSet[PeerId]]`), so every honest node computes the same demotion decision.
    * @param roundCompleted
    *   `true` iff the round produced a signed `Finished` outcome.
    * @return
    *   The next round's `peerTiers` map. Peers absent from both `priorTiers` and the round's committee are not included (CommitteeBuilder
    *   classifies absent peers at consume time using `peerQuality` -- defaults to Tier 1 unless quality proves Core).
    */
  def computeNextTiers(
    priorTiers: SortedMap[PeerId, Int],
    roundStartFacilitators: Set[PeerId],
    recentSignersWindow: SortedMap[SnapshotOrdinal, SortedSet[PeerId]],
    roundCompleted: Boolean
  ): SortedMap[PeerId, Int] =
    computeNextTiers(
      priorTiers,
      roundStartFacilitators,
      roundStartCoreFacilitators = roundStartFacilitators,
      recentSignersWindow,
      roundCompleted
    )

  /** Persist the actual Core/Tier-1 split that was frozen for the completed round.
    *
    * The legacy overload above treats every round-start member as Core and remains for callers/tests that model the pre-tiered shape.
    * Tiered consensus must supply `roundStartCoreFacilitators`: otherwise an unknown or newly admitted Tier-1 signer is incorrectly stamped
    * Core merely because it appeared in `roundStartFacilitators`.
    */
  def computeNextTiers(
    priorTiers: SortedMap[PeerId, Int],
    roundStartFacilitators: Set[PeerId],
    roundStartCoreFacilitators: Set[PeerId],
    recentSignersWindow: SortedMap[SnapshotOrdinal, SortedSet[PeerId]],
    roundCompleted: Boolean
  ): SortedMap[PeerId, Int] = {
    // The most-recent `DemotionConsecutiveMisses` signer sets. `SortedMap` iterates ascending by
    // ordinal, so `takeRight` yields the highest (most recent) ordinals.
    val recentSets: List[SortedSet[PeerId]] = recentSignersWindow.values.toList.takeRight(DemotionConsecutiveMisses)
    val windowDeepEnough: Boolean = recentSets.sizeIs >= DemotionConsecutiveMisses
    def missedRecentConsecutive(pid: PeerId): Boolean =
      windowDeepEnough && recentSets.forall(signers => !signers.contains(pid))

    val allKeys: Set[PeerId] = priorTiers.keySet ++ roundStartFacilitators
    SortedMap.from(
      allKeys.iterator.map { pid =>
        val tierUsedThisRound =
          if (roundStartCoreFacilitators.contains(pid)) Core
          else if (roundStartFacilitators.contains(pid)) priorTiers.get(pid).filter(_ == Witness).getOrElse(Tier1)
          else priorTiers.getOrElse(pid, Core)
        val nextTier = computeNextTier(
          priorTier = Some(tierUsedThisRound),
          wasInRoundStart = roundStartCoreFacilitators.contains(pid),
          missedRecentConsecutive = missedRecentConsecutive(pid),
          roundCompleted = roundCompleted
        )
        pid -> nextTier
      }
    )
  }
}
