package io.constellationnetwork.node.shared.infrastructure.consensus

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.node.shared.infrastructure.selfhealth.SelfHealthHint
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.{ConsensusOperationalState, ControllerEvidenceEntry, SnapshotOrdinal}

import eu.timepit.refined.types.numeric.NonNegLong

/** Pure derivation of per-peer controller state from the bounded `controllerEvidence` window.
  *
  * The `ControllerEvidenceEntry` schema itself lives in `modules/shared` (see its scaladoc for the per-field canonicality contract); this
  * object is the node-shared consumer logic.
  *
  * This is the stage-2 replacement architecture for CARRIED controller state (activeAdmissionScores / peerTiers / peerQuality maps copied
  * forward round over round and re-seeded from a local sidecar or `snapshot.peerHistory` on restart). Locally divergent seeds caused
  * repeated facilitatorsHash / peerHistory.perPeerDiffer wedges (alpha.92 / 129 / 147). Deriving the state purely from the signed evidence
  * window makes it a function of finalized chain facts only: two honest nodes holding the same window MUST derive identical scores, tiers,
  * and quality, regardless of any node-local carried or seeded state.
  *
  * ==Weights and clamp==
  *
  * Per evidence entry, a peer earns:
  *
  *   - `+SignWeight` (20) when it is in `completedSigners`,
  *   - `-MissWeight` (15) when it is in `roundStartFacilitators` but NOT in `completedSigners`,
  *   - `+CertWeight` (10) when it is in `admittedPeers`, and another `+CertWeight` when it is in `timeoutVoters`.
  *
  * The windowed sum is clamped to `[MinScore, MaxScore]` = [0, 150].
  *
  * Scaling rationale: the thresholds the score is consumed against mirror `ActiveFacilitatorAdmission.fromRecentSigners` defaults
  * (promote=100 / retain=70 / demote=40) and the magnitudes mirror `ConsensusPeerController.Config.default` (signatureReward=20,
  * missedActivePenalty=15, timeoutMissingPenalty=10, maxScore=150). With a `tighteningWindow` of 10 entries, a fully participating peer
  * earns 20 per entry and crosses the promote threshold (100) after 5 entries -- saturating well above promote within one window. A peer
  * that signs only half its eligible rounds nets 20-15=5 per two entries and stays pinned near the demote threshold.
  *
  * Compiled-in constants (jar-hash gated, the `TierTransitions.DemotionConsecutiveMisses` convention); promote to config if runtime tuning
  * is ever needed.
  */
object ControllerEvidenceDerivation {

  /** Score credit per entry in which the peer is a completed signer. */
  val SignWeight: Int = 20

  /** Score debit per entry in which the peer was in the round-start committee but did not sign. */
  val MissWeight: Int = 15

  /** Score credit per certified appearance (admission target / timeout-certificate voter) in an entry. */
  val CertWeight: Int = 10

  /** Lower clamp of the windowed score. */
  val MinScore: Int = 0

  /** Upper clamp of the windowed score. Mirrors `ConsensusPeerController.Config.default.maxScore`. */
  val MaxScore: Int = 150

  /** Per-peer state derived purely from the evidence window.
    *
    * @param derivedScore
    *   clamped windowed score, see weights above.
    * @param derivedTier
    *   `TierTransitions.Core` / `TierTransitions.Tier1` classification, see `derive`.
    * @param derivedQuality
    *   `(completed, participated)` windowed counters: entries in which the peer signed vs entries in which it was in the round-start
    *   committee.
    */
  final case class DerivedPeerState(
    derivedScore: Int,
    derivedTier: Int,
    derivedQuality: (Int, Int)
  )

  /** Derive per-peer controller state from the evidence window.
    *
    * Tier semantics mirror the sustained-silence demotion of `TierTransitions.computeNextTiers`: a peer that signed at least one of the
    * most recent `TierTransitions.DemotionConsecutiveMisses` entries is Core; a peer present in the window but absent from all of those
    * recent signer sets is Tier1. While the window holds fewer than `DemotionConsecutiveMisses` entries (bootstrap regime), every peer in
    * the window is Core -- the same window-deep-enough guard `computeNextTiers` applies. Peers absent from the window entirely get no
    * entry; consumers classify absent peers at read time (the CommitteeBuilder convention).
    */
  def derive(evidence: SortedMap[SnapshotOrdinal, ControllerEvidenceEntry]): SortedMap[PeerId, DerivedPeerState] = {
    // SortedMap iterates ascending by ordinal, so takeRight yields the most recent entries.
    val entries: List[ControllerEvidenceEntry] = evidence.values.toList
    val recentSignerSets: List[SortedSet[PeerId]] =
      entries.takeRight(TierTransitions.DemotionConsecutiveMisses).map(_.completedSigners)
    val windowDeepEnough: Boolean = recentSignerSets.sizeIs >= TierTransitions.DemotionConsecutiveMisses

    val keys: SortedSet[PeerId] =
      entries.foldLeft(SortedSet.empty[PeerId]) { (acc, entry) =>
        acc ++ entry.roundStartFacilitators ++ entry.completedSigners ++ entry.timeoutVoters ++ entry.admittedPeers ++ entry.evictedPeers
      }

    SortedMap.from(
      keys.iterator.map { pid =>
        val completed = entries.count(_.completedSigners.contains(pid))
        val participated = entries.count(_.roundStartFacilitators.contains(pid))
        val missed = entries.count(e => e.roundStartFacilitators.contains(pid) && !e.completedSigners.contains(pid))
        val certAppearances = entries.count(_.admittedPeers.contains(pid)) + entries.count(_.timeoutVoters.contains(pid))
        val rawScore = completed * SignWeight - missed * MissWeight + certAppearances * CertWeight
        val signedRecently = recentSignerSets.exists(_.contains(pid))
        val tier =
          if (!windowDeepEnough || signedRecently) TierTransitions.Core
          else TierTransitions.Tier1

        pid -> DerivedPeerState(
          derivedScore = clamp(rawScore, MinScore, MaxScore),
          derivedTier = tier,
          derivedQuality = (completed, participated)
        )
      }
    )
  }

  /** Derived scores only, in the shape `ActiveFacilitatorAdmission.fromRecentSigners` consumes as `activeScores`. */
  def deriveScores(evidence: SortedMap[SnapshotOrdinal, ControllerEvidenceEntry]): SortedMap[PeerId, Int] =
    derive(evidence).map { case (pid, state) => pid -> state.derivedScore }

  /** Derived `(completed, participated)` quality only, in the shape `peerQuality` consumers expect. */
  def deriveQuality(evidence: SortedMap[SnapshotOrdinal, ControllerEvidenceEntry]): SortedMap[PeerId, (Int, Int)] =
    derive(evidence).map { case (pid, state) => pid -> state.derivedQuality }

  /** Trailing-miss count at or above which a peer is classified chronically missing (see `chronicMisses`).
    *
    * Why 3: it deliberately equals `TierTransitions.DemotionConsecutiveMisses`, the sustained-silence horizon that demotes a Core peer to
    * Tier 1. Aligning the two means the moment the tier derivation sheds a silent Core peer, the chronic classification ALSO bars the
    * Core-floor from immediately re-promoting it (the demote-then-repromote loop behind the ordinal-3150040 quorum-infeasible stall). Small
    * enough to react within one evidence window; large enough that a single slow round (GC pause, network blip) does not strip a healthy
    * peer. Compiled-in constant, jar-hash gated, same convention as `DemotionConsecutiveMisses`.
    */
  val ChronicMissThreshold: Int = TierTransitions.DemotionConsecutiveMisses

  /** Number of TRAILING evidence entries in which `peer` was asked to sign (in `roundStartFacilitators`) but did not (absent from
    * `completedSigners`), counted from the most recent entry backwards.
    *
    * Streak semantics (deliberate, relied on by `chronicMisses`):
    *
    *   - an entry where the peer SIGNED resets the streak to zero, and
    *   - an entry where the peer is NOT in `roundStartFacilitators` BREAKS the streak rather than extending or skipping it. Absence means
    *     the peer was not asked to sign that round, so the entry is no evidence of unresponsiveness either way; requiring the misses to be
    *     strictly consecutive AND current keeps the classification anchored to the committee's live composition. A corollary used by
    *     `chronicMisses`: any peer with a nonzero streak is necessarily missing in the LATEST entry.
    *
    * Pure function of the evidence window only -- NEVER of local readiness observations, which would diverge `facilitatorsHash` across
    * nodes.
    */
  def consecutiveMisses(evidence: SortedMap[SnapshotOrdinal, ControllerEvidenceEntry], peer: PeerId): Int =
    evidence.values.toList.reverse.takeWhile { entry =>
      entry.roundStartFacilitators.contains(peer) && !entry.completedSigners.contains(peer)
    }.size

  /** Per-peer trailing miss counts for peers whose `consecutiveMisses` streak has reached `ChronicMissThreshold`.
    *
    * Only peers missing from the LATEST entry can have a nonzero streak (see `consecutiveMisses`), so the candidate set is exactly the last
    * entry's `roundStartFacilitators -- completedSigners`. Empty window derives an empty map (bootstrap regime, mirrored by the
    * `controllerInputsWithFallback` carried fallback).
    */
  def chronicMisses(evidence: SortedMap[SnapshotOrdinal, ControllerEvidenceEntry]): SortedMap[PeerId, Int] =
    evidence.lastOption.fold(SortedMap.empty[PeerId, Int]) {
      case (_, latest) =>
        SortedMap.from(
          (latest.roundStartFacilitators -- latest.completedSigners).iterator
            .map(pid => pid -> consecutiveMisses(evidence, pid))
            .filter { case (_, misses) => misses >= ChronicMissThreshold }
        )
    }

  /** Stage-4 read-side inputs for committee derivation: `ConsensusPeerController.chooseActive`, `CommitteeBuilder.build`,
    * `LeaderEligibility.fromRecentSigners`, and `FacilitatorSelector.selectLeaderWeighted`. The single source of truth for the
    * evidence-vs-carried decision -- the StateCreators consume these fields verbatim and contain NO conditional logic of their own, so the
    * dag-l0 / currency-l0 read sides cannot drift.
    *
    * @param activeScores
    *   per-peer admission score: evidence-derived when the window has entries, else the carried map.
    * @param peerQuality
    *   per-peer `(completed, participated)`: evidence-derived when the window has entries, else the carried map.
    * @param peerTiers
    *   per-peer tier (`TierTransitions.Core` / `Tier1`): evidence-derived when the window has entries, else the carried map. Feeds
    *   `CommitteeBuilder.build(priorTiers = ...)`; absent peers fall through to the builder's quality-based default, matching the
    *   derivation's absent-peers-get-no-entry convention.
    * @param viewChanges
    *   per-peer view-change counts for leader selection. NOT yet evidence-derived: when the window has entries this is EMPTY, because
    *   carrying the locally-mutated map would reintroduce the seed-split divergence the evidence closes. The carried map is used only in
    *   the empty-window fallback regime, so pre-warm-up behavior is unchanged. Stage-5 evidence-gap item.
    * @param selfHealth
    *   per-peer self-health hints for leader selection. Same contract as `viewChanges`: empty when evidence is present, carried only in the
    *   fallback regime. Stage-5 evidence-gap item.
    * @param chronicMisses
    *   per-peer TRAILING miss counts for peers at or above `ChronicMissThreshold` (see `chronicMisses(evidence)`). Feeds the
    *   `CommitteeBuilder.build` chronic-core replacement ladder: chronic peers are never floor-promoted into Core and are actively swapped
    *   out of it. EMPTY in the fallback (empty-window) regime -- the carried maps hold no trailing-miss evidence, and substituting local
    *   readiness observations would diverge `facilitatorsHash` across nodes.
    * @param evidenceRounds
    *   number of evidence entries the derivation consumed; `0` means the carried fallback was taken.
    */
  final case class ControllerInputs(
    activeScores: Map[PeerId, Int],
    peerQuality: Map[PeerId, (Int, Int)],
    peerTiers: SortedMap[PeerId, Int],
    viewChanges: Map[PeerId, Long],
    selfHealth: Map[PeerId, SelfHealthHint],
    chronicMisses: SortedMap[PeerId, Int],
    evidenceRounds: Int
  ) {

    /** Peers currently classified chronically missing -- the exclusion set the committee derivation consumes. */
    def chronicallyMissing: Set[PeerId] = chronicMisses.keySet
  }

  /** Stage-4 read-side switch with bootstrap fallback.
    *
    * When the evidence window holds at least one entry, the controller inputs are derived purely from the signed evidence (a function of
    * finalized chain facts -- two honest nodes holding the same window MUST compute identical inputs, regardless of carried or seeded local
    * state); `viewChanges` / `selfHealth` have no evidence-derived counterpart yet and are emitted EMPTY in that regime (see
    * `ControllerInputs`). When the window is EMPTY (first deploy / bootstrap / rollback to a pre-deploy snapshot) ALL carried maps are
    * returned unchanged so behavior matches the pre-stage-4 read until the window fills. Callers log the taken branch via `evidenceRounds`
    * (`0` => `controller_evidence=empty fallback=carried`).
    */
  def controllerInputsWithFallback(
    evidence: SortedMap[SnapshotOrdinal, ControllerEvidenceEntry],
    carriedScores: => Map[PeerId, Int],
    carriedQuality: => Map[PeerId, (Int, Int)],
    carriedTiers: => SortedMap[PeerId, Int],
    carriedViewChanges: => Map[PeerId, Long],
    carriedSelfHealth: => Map[PeerId, SelfHealthHint]
  ): ControllerInputs =
    if (evidence.isEmpty)
      ControllerInputs(
        activeScores = carriedScores,
        peerQuality = carriedQuality,
        peerTiers = carriedTiers,
        viewChanges = carriedViewChanges,
        selfHealth = carriedSelfHealth,
        chronicMisses = SortedMap.empty,
        evidenceRounds = 0
      )
    else {
      val derived = derive(evidence)

      ControllerInputs(
        activeScores = derived.map { case (pid, state) => pid -> state.derivedScore },
        peerQuality = derived.map { case (pid, state) => pid -> state.derivedQuality },
        peerTiers = derived.map { case (pid, state) => pid -> state.derivedTier },
        viewChanges = Map.empty,
        selfHealth = Map.empty,
        chronicMisses = chronicMisses(evidence),
        evidenceRounds = evidence.size
      )
    }

  /** The peerHistory payload allowed into SIGNED artifact bytes: deterministic chain-derived fields ONLY.
    *
    * `perPeer` and `recentRoundEndTimes` are the locally-divergent fields behind the alpha.92/129/147 proposal-validation wedges, so they
    * MUST stay out of signed bytes: `perPeer` is emitted empty and `recentRoundEndTimes` as `None`. What remains -- `recentProofSizes`,
    * `recentSigners`, `controllerEvidence`, `penaltyUntil` -- is byte-identically derived from consensus-agreed outcome fields on every
    * honest node. Shared between the dag-l0 and currency-l0 advancers (via the outcome `signedArtifactPeerHistory` methods) so the signed
    * subset cannot drift between layers.
    */
  def signedArtifactOperationalState(
    recentProofSizes: SortedMap[SnapshotOrdinal, Int],
    recentSigners: SortedMap[SnapshotOrdinal, SortedSet[PeerId]],
    controllerEvidence: Option[SortedMap[SnapshotOrdinal, ControllerEvidenceEntry]],
    penaltyUntil: Option[SortedMap[PeerId, SnapshotOrdinal]]
  ): ConsensusOperationalState =
    ConsensusOperationalState(
      perPeer = SortedMap.empty,
      recentProofSizes = recentProofSizes,
      // Option-wrap convention at the persistence boundary: emit `Some` only when non-empty
      // so `dropNullValues = true` keeps empty windows out of the encoded bytes.
      recentSigners = if (recentSigners.nonEmpty) Some(recentSigners) else None,
      recentRoundEndTimes = None,
      controllerEvidence = controllerEvidence.filter(_.nonEmpty),
      penaltyUntil = penaltyUntil.filter(_.nonEmpty)
    )

  /** Append the just-finalized round's entry and trim to the rolling window.
    *
    * Same window arithmetic the `recentSigners` / `recentProofSizes` / `recentRoundEndTimes` windows use: entries older than `key -
    * tighteningWindow + 1` are dropped. Shared between the dag-l0 and currency-l0 StateAdvancers so the trim logic cannot drift.
    */
  def appendBounded(
    prior: SortedMap[SnapshotOrdinal, ControllerEvidenceEntry],
    key: SnapshotOrdinal,
    entry: ControllerEvidenceEntry,
    tighteningWindow: Int
  ): SortedMap[SnapshotOrdinal, ControllerEvidenceEntry] = {
    val minOrdinalValue = math.max(0L, key.value.value - tighteningWindow.toLong + 1L)

    prior.updated(key, entry).filter { case (ord, _) => ord.value.value >= minOrdinalValue }
  }

  /** Advance the cert-anchored `penaltyUntil` map for one finalized round.
    *
    * Absolute-ordinal penalties, never countdowns: an EvictionCertificate applied at ordinal N writes `target -> N + D` (D =
    * `penaltyDurationOrdinals`); an AdmissionCertificate removes the target's entry (certified re-admission overrides any remaining
    * penalty); entries whose ordinal is at or below the current key are dropped as expired. Every step is a pure comparison or map update
    * against consensus-agreed inputs -- there is no per-round decrement that a restart could observe half-applied.
    *
    * Order matters: expire, then write evictions, then clear admissions LAST, so a peer that is somehow both evicted and admitted in the
    * same round (should not happen, defended against) ends up cleared -- mirroring the `ReadmissionMaintenance.step` ordering convention.
    */
  def nextPenaltyUntil(
    prior: SortedMap[PeerId, SnapshotOrdinal],
    certifiedEvictions: Set[PeerId],
    certifiedAdmissions: Set[PeerId],
    currentOrdinal: SnapshotOrdinal,
    penaltyDurationOrdinals: Int
  ): SortedMap[PeerId, SnapshotOrdinal] = {
    val duration = NonNegLong.from(penaltyDurationOrdinals.toLong).getOrElse(NonNegLong.MinValue)
    val until = currentOrdinal.plus(duration)
    val unexpired = prior.filter { case (_, ord) => ord.value.value > currentOrdinal.value.value }
    val withEvictions = certifiedEvictions.foldLeft(unexpired)((acc, pid) => acc.updated(pid, until))

    withEvictions -- certifiedAdmissions
  }

  private def clamp(value: Int, min: Int, max: Int): Int =
    math.max(min, math.min(max, value))
}
