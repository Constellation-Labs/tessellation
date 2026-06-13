package io.constellationnetwork.node.shared.infrastructure.consensus

import scala.collection.immutable.SortedMap

import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.peer.PeerId

/** Deterministic derivation of the three v19 committees from a flat candidate set, the carried-forward per-peer tier map, AND the
  * consensus-agreed per-peer participation history.
  *
  * ==Three committees==
  *
  *   - '''Core''' (Tier 2): full facilitators. The CERT quorum (B1 / B2 / VCC builders) is gated on Core: `q = ceil(coreFacilitators.size *
  *     quorumThresholdFraction)`. Core peers also form the leader pool (only Core peers are eligible to lead a round).
  *   - '''Tier1''': witness-eligible (B1/B2/VCC witness pool). Tier 1 peers DO sign each round's `signedMajorityArtifact` and earn rewards
  *     proportionally. They cannot lead, and they do NOT count toward the cert quorum denominator -- a Tier 1 peer being silent cannot
  *     wedge a B1/B2/VCC certificate. The SNAPSHOT finalization threshold is a separate, more permissive `(roundStartFacilitators.size / 2)
  *     + 1` computed over Core + Tier 1; safety in finalization is enforced via VoteLock + VCC, not by tightening the threshold.
  *   - '''Witness''' (Tier 0): observation only. Open membership; peers fall here only via explicit eviction outside this builder. In
  *     practice the v19 tier-transition path never writes Witness; this tier is reserved for future explicit-eviction policy.
  *
  * ==Reward and signer pool==
  *
  * `lastArtifact.proofs.map(_.id)` is the reward facilitator set (see `Rewards.distribute`). Anyone who signs the finalized snapshot earns
  * an equal share. Tier 1 peers are in `roundStartFacilitators`, sign just like Core peers, and split the facilitator reward pool evenly.
  * There is no Core-vs-Tier-1 stratification in today's reward math.
  *
  * ==Tier assignment rule==
  *
  * Each peer's effective tier for the round is computed by consulting, in order:
  *
  *   1. '''Quality degradation override''': if `peerQuality(pid)` shows `participated >= minObservations` AND `completed/participated <
  *      minRatio`, the peer is Tier 1 regardless of `priorTiers`. This is the structural protection: a peer whose cumulative track record
  *      has fallen below the quality bar cannot gate liveness, even if their previously-recorded `peerTiers` entry said Core. Re-derived
  *      every round, so a peer that recovers ratio above the threshold returns to whatever `priorTiers` says next round. 2.
  *      '''Carried-forward classification''': `priorTiers.get(pid)`, if present. 3. '''Quality-proven bootstrap''': for peers absent from
  *      `priorTiers` (new joiners), Core IFF `peerQuality` shows them above `minRatio` with `participated >= minObservations`. This lets
  *      demonstrated-good peers enter Core on their first appearance. 4. '''Default''': Tier 1. New peers without proven participation join
  *      the witness pool, not the liveness quorum. This is the replacement for the original v19 "everyone defaults to Core" bootstrap,
  *      which let chronic-but-unclassified community peers wedge the cluster.
  *
  * ==Core floor==
  *
  * If the derived Core committee is below the per-environment `coreCommitteeSize`, peers are promoted from Tier 1 deterministically. The
  * promotion order ranks Tier 1 peers by their `peerQuality` (descending ratio, then descending completed count, then PeerId lex as
  * tie-break) so the floor pulls in the most demonstrably reliable Tier 1 peers first. At genesis (empty `peerQuality`), this collapses to
  * pure lex ordering so the cluster bootstraps from scratch.
  *
  * The floor is consensus-critical: divergent values across operators would derive divergent Core committees and silently fork the cluster.
  * `coreCommitteeSize` is keyed by `AppEnvironment`, resolved to a flat `Option[Int]` at the consensus construction site, and (as of v20)
  * IS folded into `deterministicConfigHash` (treated as the dev default `3` when absent). Mismatched values are therefore rejected at
  * handshake by the config hash, in addition to the jar hash already gating the peer connection. `minObservations` and `minRatio` reuse the
  * existing `minParticipationObservations` / `minParticipationRatio` config knobs.
  *
  * ==Chronic-core replacement ladder==
  *
  * `chronicMisses` (evidence-derived, see `ControllerEvidenceDerivation.chronicMisses`) marks peers whose trailing asked-but-silent streak
  * has reached `ChronicMissThreshold`. Before this ladder, the Core floor re-promoted exactly those peers whenever healthy supply was short
  * (the live failure: a peer absent from `completedSigners` for 112 consecutive rounds kept its Core seat via the floor; with core=4,
  * quorum=3, and 2 dead members, every round abandoned `ready_participation_quorum_infeasible`). The ladder, applied in order:
  *
  *   1. '''Exclude''': every chronically-missing Core member loses its seat (demoted to Tier 1 for the round -- still signs and earns, no
  *      longer in the quorum denominator).
  *   1. '''Replace''': each excluded member is swapped one-for-one for a non-chronic Tier 1 reserve, highest `activeScores` first, PeerId
  *      lex tie-break, supply permitting.
  *   1. '''Floor''': the floor then tops Core up to `coreFloor` from the remaining NON-CHRONIC reserves only (quality ranking, as before).
  *      Chronic peers are never floor-promoted.
  *   1. '''Shrink''': if non-chronic supply cannot reach `coreFloor`, the Core stays SMALLER rather than padding with chronic peers -- the
  *      quorum is proportional (`max(1, ceil(size * quorumThresholdFraction))`, see `QuorumPolicy`), so a smaller all-healthy Core is
  *      strictly more live than a floor-sized one with dead seats.
  *   1. '''Liveness fallback''': if the healthy Core would land below `MinViableCoreSize`, the least-bad chronic peers (lowest miss count,
  *      PeerId lex tie-break) are re-admitted to reach it, so a mostly-dead network still forms committees.
  *
  * All inputs to the ladder are evidence-derived or consensus-agreed; it is byte-deterministic across honest nodes. With an empty
  * `chronicMisses` (fallback regime, or no chronic peers) every step is inert and the derivation is unchanged.
  *
  * ==Determinism contract==
  *
  * Every input is a consensus-agreed signed outcome field (carried `priorTiers`, `peerQuality`) or a deterministic local computation from
  * one (the candidate set, produced by the same filtering pipeline on every node). Output is byte-stable across honest nodes.
  */
object CommitteeBuilder {

  import TierTransitions.{Core, Tier1, Witness}

  /** Liveness-fallback minimum Core size for the chronic-exclusion ladder.
    *
    * The cert quorum is computed FROM the Core size (`max(1, ceil(size * quorumThresholdFraction))`, see `QuorumPolicy`), so every size is
    * arithmetically quorum-viable; 2 is the smallest committee where leader rotation and mutual attestation are meaningful (the
    * `minLeaderPoolSize` rationale: with a single peer, `viewNumber % 1 = 0` makes view change a no-op). Hence `max(2, quorum-viable)` = 2.
    * Compiled-in constant, jar-hash gated.
    */
  val MinViableCoreSize: Int = 2

  /** Final per-committee classification result. `core`, `tier1`, `witness` partition `candidates` exactly: every peer in `candidates` lands
    * in exactly one of the three.
    *
    * `effectiveTiers` is the tier map AFTER applying the bootstrap default, the quality-degradation override, and any Core-floor
    * promotions. This is what the StateCreator persists into the round's `roundStartFacilitators` -- the round's view of who is Core vs
    * Tier 1 vs Witness.
    *
    * The chronic-ladder diagnostics (`chronicExcluded` / `chronicReplacements` / `chronicReadmitted`, each `(PeerId, trailing miss count)`
    * or plain ids) report what the ladder did this round, for the StateCreators' observability line and metrics; they carry no additional
    * classification beyond the three lists above.
    */
  final case class Committees(
    core: List[PeerId],
    tier1: List[PeerId],
    witness: List[PeerId],
    effectiveTiers: SortedMap[PeerId, Int],
    chronicExcluded: List[(PeerId, Int)],
    chronicReplacements: List[PeerId],
    chronicReadmitted: List[(PeerId, Int)]
  )

  /** Derive (core, tier1, witness) from the candidate set, applying quality-aware tier defaults and Core-floor promotion.
    *
    * @param candidates
    *   The set of eligible peers for the next round (filtered in the StateCreator by the two remaining behavioural gates: removal-penalty
    *   and re-admission probation; the chronic-non-signer / prior-round-missing / tightening-window / candidate-deferral filters were
    *   retired in v19 and replaced by this tier partition). Order preserved in the output via stable PeerId-hex sorting of the promotion
    *   set.
    * @param priorTiers
    *   `lastOutcome.peerTiers` -- the carried-forward classification. Absent peers fall through to quality-based default.
    * @param peerQuality
    *   `lastOutcome.peerQuality` -- per-peer `(completed, participated)` participation history. Used for the quality-degradation override
    *   (any-tier -> Tier 1 if cumulative ratio drops below `minRatio`), the quality-proven bootstrap (Tier 1 -> Core for new peers above
    *   the bar), and the Core-floor promotion ranking. Empty at genesis -> ranking falls back to lex order.
    * @param coreFloor
    *   Per-environment minimum Core size.
    * @param minObservations
    *   Minimum `participated` count before the quality criteria fire. Peers below this threshold are treated as "insufficient evidence" and
    *   fall through to the default. Reuses `config.minParticipationObservations`.
    * @param minRatio
    *   Minimum `completed/participated` ratio for Core eligibility. Reuses `config.minParticipationRatio`.
    * @param nonCorePeers
    *   Peers allowed into the round as non-Core participants only. They are forced to Tier 1 unless they were already Witness, and they are
    *   skipped by Core-floor promotion. This is used for probationary expansion: the peer can receive facilities and produce signer
    *   evidence, but cannot raise the liveness quorum denominator before the integral controller graduates it. Non-bypassable: even the
    *   chronic ladder's liveness fallback never re-admits a probation peer into Core. The reward-rotation lane ALSO excludes these peers
    *   from both the rotate-in pool and the rotate-out (tier1) candidates, so probation peers stay on their own rehab lane untouched.
    * @param chronicMisses
    *   `controllerInputs.chronicMisses` -- evidence-derived trailing miss counts for chronically-missing peers (see the chronic-core
    *   replacement ladder above). Empty in the fallback regime or when no peer is chronic, which makes the whole ladder inert.
    * @param activeScores
    *   `controllerInputs.activeScores` -- evidence-derived per-peer scores. Used ONLY to rank one-for-one chronic replacements (highest
    *   score first, PeerId lex tie-break); the Core-floor top-up keeps its original quality ranking.
    * @param rotationKey
    *   the round ordinal, used by the bounded reward-rotation lane to test the epoch boundary (`key.value % rewardRotationEpochRounds`).
    *   `None` disables rotation regardless of the other rotation parameters; defaulted so call sites that do not rotate are unaffected.
    * @param recentParticipants
    *   `ControllerEvidenceDerivation.recentParticipants(evidence, effectiveEligibilityWindow)` -- peers demonstrated-live within the
    *   reward-rotation eligibility window. The caller resolves `effectiveEligibilityWindow` (the configured
    *   `rewardRotationEligibilityWindow`, or the full evidence window `tighteningWindow` when 0) and applies it BEFORE passing this set, so
    *   the rotation horizon can exceed the demotion-aligned `DemotionConsecutiveMisses` window (a rotated-out peer must stay eligible past
    *   the next epoch boundary; see `ControllerEvidenceDerivation.recentParticipants(evidence, window)` for the fairness invariant). The
    *   reward-rotation eligible-waiting pool is `candidates intersect recentParticipants minus core minus tier1` AND minus `nonCorePeers`
    *   (probation peers stay on their own rehab lane, never rotated). Empty disables rotation (no demonstrated peer to seat).
    * @param idleWindows
    *   `ControllerEvidenceDerivation.idleWindows(evidence, _)` -- trailing entries since each peer last signed; ranks the rotated-in peer
    *   (longest-idle first).
    * @param tenureWindows
    *   `ControllerEvidenceDerivation.tenureWindows(evidence, _)` -- consecutive trailing entries each peer has signed; ranks the
    *   rotated-out Tier-1 peer (longest-serving first).
    * @param rewardRotationEpochRounds
    *   epoch length in ordinals for the reward-rotation lane. `<= 0` (the default) leaves the lane inert and `build` byte-identical to its
    *   pre-rotation output.
    * @param lotteryHash
    *   `FacilitatorSelector.rendezvousScore`-equivalent `(peer, key) => BigInt`, reused as the equally-idle tiebreak in the rotation lane.
    */
  def build(
    candidates: List[PeerId],
    priorTiers: SortedMap[PeerId, Int],
    peerQuality: Map[PeerId, (Int, Int)],
    coreFloor: Int,
    minObservations: Int,
    minRatio: Double,
    nonCorePeers: Set[PeerId] = Set.empty,
    chronicMisses: Map[PeerId, Int] = Map.empty,
    activeScores: Map[PeerId, Int] = Map.empty,
    rotationKey: Option[SnapshotOrdinal] = None,
    recentParticipants: Set[PeerId] = Set.empty,
    idleWindows: PeerId => Int = _ => 0,
    tenureWindows: PeerId => Int = _ => 0,
    rewardRotationEpochRounds: Int = 0,
    lotteryHash: (PeerId, SnapshotOrdinal) => BigInt = FacilitatorSelector.lotteryHash
  ): Committees = {
    def hasSufficientHistory(pid: PeerId): Option[Double] =
      peerQuality.get(pid).flatMap {
        case (completed, participated) if participated >= minObservations =>
          Some(completed.toDouble / participated.toDouble)
        case _ => None
      }

    def isQualityDegraded(pid: PeerId): Boolean =
      hasSufficientHistory(pid).exists(_ < minRatio)

    def isQualityProven(pid: PeerId): Boolean =
      hasSufficientHistory(pid).exists(_ >= minRatio)

    val effectiveTier: PeerId => Int = pid =>
      if (nonCorePeers.contains(pid))
        priorTiers.get(pid).filter(_ == Witness).getOrElse(Tier1)
      else if (isQualityDegraded(pid)) Tier1
      else
        priorTiers.get(pid) match {
          case Some(tier) => tier
          case None       => if (isQualityProven(pid)) Core else Tier1
        }

    val (rawCore, rawNonCore) = candidates.partition(effectiveTier(_) == Core)
    val (rawTier1, rawWitness) = rawNonCore.partition(effectiveTier(_) == Tier1)

    val isChronic: PeerId => Boolean = chronicMisses.contains

    // Chronic-core replacement ladder (see object scaladoc). Step 1, EXCLUDE: every
    // chronically-missing Core member loses its seat for the round. In the evidence
    // regime the derived tiers usually demote such peers already (chronic implies
    // absent from the recent signer sets); the partition is the unconditional
    // backstop so the invariant "chronic implies not Core unless ladder-readmitted"
    // holds regardless of which path classified the peer Core.
    val (chronicCore, healthyCore) = rawCore.partition(isChronic)

    // Peers permitted to take a Core seat via replacement or floor promotion:
    // non-chronic Tier 1 reserves outside the probation set. Chronic peers are
    // categorically barred from BOTH mechanisms -- this is the fix for the floor
    // re-promoting dead peers into the quorum denominator.
    val corePromotablePool = rawTier1.filterNot(pid => isChronic(pid) || nonCorePeers.contains(pid))

    // Step 2, REPLACE: one-for-one swap for each excluded Core member, highest
    // evidence score first, PeerId lex tie-break. Evidence-derived scores only --
    // never local readiness observations.
    val replacements = corePromotablePool
      .sortBy(pid => (-activeScores.getOrElse(pid, 0), pid.value.value))
      .take(chronicCore.size)
    val replacementSet = replacements.toSet

    // Step 3, FLOOR: top Core up to `coreFloor` from the remaining non-chronic pool.
    // Ranking pulls the most demonstrably reliable Tier 1 peers first (high ratio,
    // high completed count); at genesis with empty peerQuality this collapses to pure
    // lex order, preserving bootstrap behavior. Every honest node sees the same
    // peerQuality + priorTiers and therefore makes the same promotion choices.
    def qualityRank(pid: PeerId): (Int, Int, Int, String) =
      peerQuality.get(pid) match {
        case Some((completed, participated)) if participated > 0 =>
          val ratioScaled = ((completed.toDouble / participated.toDouble) * 1000000).toInt
          // Sort key: hasHistory(0=has, 1=none) asc, -ratio asc, -completed asc, peerId asc
          // (0, ...) sorts before (1, ...) so peers with data rank ahead of bootstrap-blank peers.
          (0, -ratioScaled, -completed, pid.value.value)
        case _ =>
          (1, 0, 0, pid.value.value)
      }
    val promotionDeficit = math.max(0, coreFloor - healthyCore.size - replacements.size)
    val promoted = corePromotablePool
      .filterNot(replacementSet.contains)
      .sortBy(qualityRank)
      .take(promotionDeficit)

    // Step 4, SHRINK, is implicit: when the non-chronic pool runs out before the
    // floor is met, Core simply stays smaller -- no chronic padding.
    //
    // Step 5, LIVENESS FALLBACK: if the healthy Core landed below MinViableCoreSize,
    // re-admit the least-bad chronic peers (lowest trailing miss count, PeerId lex
    // tie-break) to reach it. The target is capped at what the pre-ladder derivation
    // would have produced (max(coreFloor, rawCore.size)) so the fallback can never
    // INFLATE the Core beyond the legacy behavior (e.g. coreFloor=0 bootstrap
    // configurations). Probation peers stay barred even here.
    val healthySize = healthyCore.size + replacements.size + promoted.size
    val readmitTarget = math.min(MinViableCoreSize, math.max(coreFloor, rawCore.size))
    val readmitted = (chronicCore ++ rawTier1.filter(isChronic))
      .filterNot(nonCorePeers.contains)
      .sortBy(pid => (chronicMisses.getOrElse(pid, Int.MaxValue), pid.value.value))
      .take(math.max(0, readmitTarget - healthySize))
    val readmittedSet = readmitted.toSet

    val finalCore: List[PeerId] = healthyCore ++ replacements ++ promoted ++ readmitted
    val finalCoreSet = finalCore.toSet
    val rawWitnessSet = rawWitness.toSet
    // Preserve original ordering for the tier1 set so the StateCreator's upstream
    // candidate order (FacilitatorSelector.select output) is respected; excluded
    // chronic Core members land here (they still sign and earn, just not in the
    // quorum denominator).
    val splitTier1 = candidates.filterNot(pid => finalCoreSet.contains(pid) || rawWitnessSet.contains(pid))
    val splitWitness: List[PeerId] = rawWitness

    // Bounded one-slot reward rotation (see RewardRotation scaladoc). Cycles a single
    // Tier-1 signing seat among demonstrated-live peers so reward share does not stay
    // pinned to the always-on signer set. Core is NEVER touched. Applied AFTER the
    // core/tier1/witness split so it only ever moves a Tier-1 <-> Witness pair. The
    // eligible-waiting pool is demonstrated-live candidates outside core and tier1 --
    // by construction those are the Witness peers that have actually signed within the
    // eligibility window, so the rotated-in peer is one the tier derivation already
    // treats as Core-eligible. Probation (`nonCorePeers`) peers are EXCLUDED from BOTH
    // the rotate-in pool AND the rotate-out (tier1) candidates: they are on their own
    // bounded rehab lane and must not be disturbed by reward rotation (a probation peer
    // forced into the seat, or a probation peer's seat rotated away, would couple the two
    // lanes). Inert (no-op, byte-identical output) when the lane is disabled: `rotationKey`
    // empty, `rewardRotationEpochRounds <= 0`, or no eligible candidate.
    val splitTier1Set = splitTier1.toSet
    val eligibleWaiting =
      candidates.filter(pid =>
        recentParticipants.contains(pid) &&
          !finalCoreSet.contains(pid) &&
          !splitTier1Set.contains(pid) &&
          !nonCorePeers.contains(pid)
      )
    val rotatableTier1 = splitTier1.filterNot(nonCorePeers.contains)
    val rotation = rotationKey.flatMap { key =>
      RewardRotation.rotateOneTier1(
        key = key,
        core = finalCoreSet,
        tier1 = rotatableTier1,
        eligibleWaiting = eligibleWaiting,
        idle = idleWindows,
        tenure = tenureWindows,
        epochRounds = rewardRotationEpochRounds,
        lotteryHash = lotteryHash
      )
    }
    val (finalTier1, finalWitness) =
      rotation match {
        case Some((rotateOut, rotateIn)) =>
          // Move rotateIn from witness into tier1 (appended, preserving the upstream order
          // of the retained tier1 members); move rotateOut from tier1 to the FRONT of
          // witness/reserve. Both lists keep their size invariant (remove one, add one).
          (splitTier1.filterNot(_ == rotateOut) :+ rotateIn, rotateOut :: splitWitness.filterNot(_ == rotateIn))
        case None =>
          (splitTier1, splitWitness)
      }

    // Stamp the effective tier on every classified peer for the round's persisted view.
    // Carry-forward semantics: peers in priorTiers but NOT in candidates retain their
    // classification across rounds where they were not in the eligible pool. The
    // quality-degradation override is applied every round so a degraded peer in
    // priorTiers as Core appears as Tier 1 in effectiveTiers for THIS round; if their
    // quality recovers, next round they revert to whatever priorTiers said.
    val effectiveTiers: SortedMap[PeerId, Int] =
      priorTiers ++
        finalCore.iterator.map(_ -> Core) ++
        finalTier1.iterator.map(_ -> Tier1) ++
        finalWitness.iterator.map(_ -> Witness)

    Committees(
      core = finalCore,
      tier1 = finalTier1,
      witness = finalWitness,
      effectiveTiers = effectiveTiers,
      chronicExcluded = chronicCore.filterNot(readmittedSet.contains).map(pid => pid -> chronicMisses.getOrElse(pid, 0)),
      chronicReplacements = replacements,
      chronicReadmitted = readmitted.map(pid => pid -> chronicMisses.getOrElse(pid, 0))
    )
  }
}
