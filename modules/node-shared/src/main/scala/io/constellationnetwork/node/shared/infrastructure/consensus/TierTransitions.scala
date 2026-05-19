package io.constellationnetwork.node.shared.infrastructure.consensus

import scala.collection.immutable.{SortedMap, SortedSet}

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

  /** Compute the next tier for a single peer given the prior tier and round outcome.
    *
    * @param priorTier
    *   The peer's tier carried from the prior outcome (`lastOutcome.peerTiers`). `None` means the peer has no classification yet --
    *   bootstrap default, treated as Tier 2.
    * @param wasInRoundStart
    *   Was the peer in `roundStartFacilitators[N]`? Only Tier 2 peers (or bootstrap-default peers) can be in `roundStartFacilitators`, so
    *   this is effectively a "did this peer participate as Core this round" signal.
    * @param wasInRecentSigners
    *   Was the peer in `recentSigners[N]` (i.e. did they sign the finalized outcome)?
    * @param roundCompleted
    *   Did this round produce a `Finished` outcome with a signed artifact? Failed rounds do not cascade-demote (return the prior tier
    *   unchanged).
    * @return
    *   The peer's tier going into round N+1.
    */
  def computeNextTier(
    priorTier: Option[Int],
    wasInRoundStart: Boolean,
    wasInRecentSigners: Boolean,
    roundCompleted: Boolean
  ): Int = {
    val current = priorTier.getOrElse(Core)
    if (!roundCompleted) current
    else if (current == Core && wasInRoundStart && !wasInRecentSigners) Tier1
    else current
  }

  /** Apply `computeNextTier` to every peer that participated in the round, producing the next round's `peerTiers` map.
    *
    * @param priorTiers
    *   `lastOutcome.peerTiers` -- the carried-forward classification from the prior round.
    * @param roundStartFacilitators
    *   The canonical round-start committee. Demotion gates on membership here so a peer who was withdrawn mid-round (and never expected to
    *   sign) is not penalized.
    * @param recentSignersForRound
    *   The signer set for the completed round (consensus-agreed; same value across honest nodes since `recentSigners` is sliced off
    *   `completedFacilitators`).
    * @param roundCompleted
    *   `true` iff the round produced a signed `Finished` outcome.
    * @return
    *   The next round's `peerTiers` map. Peers absent from both `priorTiers` and the round's committee are not included (CommitteeBuilder
    *   classifies absent peers at consume time using `peerQuality` -- defaults to Tier 1 unless quality proves Core).
    */
  def computeNextTiers(
    priorTiers: SortedMap[PeerId, Int],
    roundStartFacilitators: Set[PeerId],
    recentSignersForRound: SortedSet[PeerId],
    roundCompleted: Boolean
  ): SortedMap[PeerId, Int] = {
    val allKeys: Set[PeerId] = priorTiers.keySet ++ roundStartFacilitators
    SortedMap.from(
      allKeys.iterator.map { pid =>
        val nextTier = computeNextTier(
          priorTier = priorTiers.get(pid),
          wasInRoundStart = roundStartFacilitators.contains(pid),
          wasInRecentSigners = recentSignersForRound.contains(pid),
          roundCompleted = roundCompleted
        )
        pid -> nextTier
      }
    )
  }
}
