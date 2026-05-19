package io.constellationnetwork.node.shared.infrastructure.consensus

import scala.collection.immutable.SortedMap

import io.constellationnetwork.schema.peer.PeerId

/** Deterministic derivation of the three v19 committees from a flat candidate set and the carried-forward per-peer tier map.
  *
  * ==Three committees==
  *
  *   - '''Core''' (Tier 2): full facilitators, in the LIVENESS quorum. Quorum threshold is computed against `coreFacilitators.value.size`
  *     only; demotions out of Core never change the active quorum denominator without consensus-agreement.
  *   - '''Tier1''': witness-eligible (B1/B2/VCC witness pool), not in the LIVENESS quorum. Demoted by `TierTransitions.computeNextTier` on
  *     completed rounds where they were in `roundStartFacilitators` but missing from `recentSigners`.
  *   - '''Witness''' (Tier 0): observation only. Open membership; peers fall here only via explicit eviction outside this builder.
  *
  * ==Bootstrap default==
  *
  * Peers with no carried-forward tier (`priorTiers.get(pid) == None`) default to Core. No seedlist allowlist is required: the cluster boots
  * with all eligible peers in Core, and `TierTransitions` propagates demotions only after the first round completes and signers are
  * observed.
  *
  * ==Core floor==
  *
  * If the derived Core committee is below the per-environment `coreCommitteeSize`, peers are promoted from Tier1 (deterministically:
  * lexicographic-sorted by PeerId hex value so every honest node selects the same promotion set) until the floor is met. If Tier1 is also
  * insufficient, the floor is honored to whatever degree the candidate pool can supply -- the builder never invents peers.
  *
  * The floor is consensus-critical: divergent values across operators would derive divergent Core committees and silently fork the cluster.
  * `coreCommitteeSize` is keyed by `AppEnvironment` and is NOT included in `deterministicConfigHash` (the jar hash gates peer connection;
  * same precedent as `maxFacilitatorCount`).
  *
  * ==Determinism contract==
  *
  * Every input is a consensus-agreed signed outcome field (carried `priorTiers`) or a deterministic local computation from one (the
  * candidate set produced by the same filtering pipeline on every node). Output is byte-stable across honest nodes.
  */
object CommitteeBuilder {

  import TierTransitions.{Core, Tier1, Witness}

  /** Final per-committee classification result. `core`, `tier1`, `witness` partition `candidates` exactly: every peer in `candidates` lands
    * in exactly one of the three.
    *
    * `effectiveTiers` is the tier map AFTER applying the bootstrap default and any Core floor promotions. This is what the StateCreator
    * persists into the round's `roundStartFacilitators` -- the round's view of who is Core vs Tier 1 vs Witness.
    */
  final case class Committees(
    core: List[PeerId],
    tier1: List[PeerId],
    witness: List[PeerId],
    effectiveTiers: SortedMap[PeerId, Int]
  )

  /** Derive (core, tier1, witness) from the candidate set, applying tier defaults and Core-floor promotion.
    *
    * @param candidates
    *   The set of eligible peers for the next round (already filtered by the chronic-non-signer / penalized / deferred / probation /
    *   tightening pipeline in the StateCreator). Order preserved in the output via stable lexicographic-by-PeerId sorting of the promotion
    *   set.
    * @param priorTiers
    *   `lastOutcome.peerTiers` -- the carried-forward classification. Absent peers default to Tier 2 (Core).
    * @param coreFloor
    *   Per-environment minimum Core size. Promotions from Tier1 are applied deterministically (lex-sorted) until the floor is met or Tier1
    *   is exhausted.
    */
  def build(
    candidates: List[PeerId],
    priorTiers: SortedMap[PeerId, Int],
    coreFloor: Int
  ): Committees = {
    val effectiveTier: PeerId => Int = pid => priorTiers.getOrElse(pid, Core)

    val (rawCore, rawNonCore) = candidates.partition(effectiveTier(_) == Core)
    val (rawTier1, rawWitness) = rawNonCore.partition(effectiveTier(_) == Tier1)

    // Core-floor promotion: if rawCore.size < coreFloor, deterministically promote
    // lexicographic-sorted Tier1 peers into Core until the floor is met. Promotion
    // is by stable PeerId-hex ordering so every honest node makes the same call.
    val promotionDeficit = math.max(0, coreFloor - rawCore.size)
    val sortedTier1ByHex = rawTier1.sortBy(_.value.value)
    val (promoted, remainingTier1Sorted) = sortedTier1ByHex.splitAt(promotionDeficit)
    // Preserve original ordering for the un-promoted tier1 set so the StateCreator's
    // upstream candidate order (FacilitatorSelector.select output) is respected.
    val promotedSet = promoted.toSet
    val finalTier1 = rawTier1.filterNot(promotedSet.contains)

    val finalCore: List[PeerId] = rawCore ++ promoted
    val finalWitness: List[PeerId] = rawWitness

    // Stamp the effective tier on every classified peer for the round's persisted view.
    // We DO NOT modify entries for peers outside `candidates`: those are carried forward
    // unchanged so demoted peers retain their classification across rounds where they were
    // not in the eligible pool (mirrors `peerQuality` carry-forward semantics).
    val effectiveTiers: SortedMap[PeerId, Int] =
      priorTiers ++
        finalCore.iterator.map(_ -> Core) ++
        finalTier1.iterator.map(_ -> Tier1) ++
        finalWitness.iterator.map(_ -> Witness)

    Committees(finalCore, finalTier1, finalWitness, effectiveTiers)
  }
}
