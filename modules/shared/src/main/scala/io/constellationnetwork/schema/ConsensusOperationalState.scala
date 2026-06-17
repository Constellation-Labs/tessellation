package io.constellationnetwork.schema

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.schema.peer.PeerId

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

// Per-peer behavior counters consolidated into a single record so each PeerId
// appears once across all five per-peer dimensions, instead of being duplicated
// as a key across five separate maps. PeerId is a 128-char hex string (~128
// bytes per occurrence); with 150 mainnet peers active in 2-4 of these
// dimensions, the prior layout wasted tens of KB per snapshot in duplicated
// keys.
//
// `empty` is the identity element: a peer absent from the per-peer map is
// equivalent to a peer with `PerPeerOperationalRecord.empty`. Code that
// previously checked `if (penalties.contains(pid))` now checks
// `if (record.removalPenalty > 0)`.
@derive(eqv, show, encoder, decoder)
final case class PerPeerOperationalRecord(
  quality: (Int, Int),
  removalPenalty: Int,
  cumulativeMissCount: Long,
  readmissionCountdown: Int,
  deferralCountdown: Int,
  // Cumulative count of view changes this peer caused as a failed
  // leader-of-the-view. Derived at round finalization from
  // `(roundStartFacilitators, entropy, finalView, priorOutcome.peerQuality, ...)`
  // by recomputing the deterministic leader at each view in [0, finalView) and
  // crediting that peer with one view-change-caused. Persisted on the snapshot so
  // the chronic-leader filter survives cluster cold-restart, the same way
  // `cumulativeMissCount` and `removalPenalty` do.
  //
  // MUST be `Option[Long]`, not `Long = 0L`: the derevo-derived JSON decoder does NOT
  // respect Scala default values. A `Long` field with a default would be treated as
  // required by the decoder, breaking back-compat with pre-v16 snapshots that have
  // no `viewChangesCaused` key under `peerHistory.perPeer.<pid>`. Wrapping in Option
  // makes the field truly optional at decode time; combined with
  // `Printer(dropNullValues = true)` in production, `None` is dropped from JSON
  // entirely so v16-encoded snapshots are byte-identical to pre-v16 for peers that
  // have not yet caused a view change. Treat `None` as 0 at every read site.
  viewChangesCaused: Option[Long] = None,
  // v19 multi-committee tier classification. Three deterministic tiers govern the
  // peer's role in a round:
  //   - Tier 2 (Core): full facilitator, gates LIVENESS (quorum is computed against
  //     coreFacilitators only).
  //   - Tier 1: witness-eligible (B1/B2/VCC witness pool), not in the active LIVENESS
  //     quorum. As of v22 a Tier 2 peer is demoted to Tier 1 only after SUSTAINED
  //     silence -- absent from the most-recent `TierTransitions.DemotionConsecutiveMisses`
  //     signer sets of the `recentSigners` window -- not on a single missed signature.
  //     Failed rounds do not cascade demote; only completed rounds update tier.
  //   - Tier 0 (Witness): open membership, observation only.
  // Computed by `TierTransitions.computeNextTiers` at every round-finalize from
  // consensus-agreed inputs (prior tiers + roundStartFacilitators + the recentSigners
  // window + round outcome) so every honest node converges on the same per-peer tier
  // byte-identically.
  //
  // Two DIFFERENT defaults for an unclassified peer, intentionally asymmetric (see the
  // `TierTransitions` header): at ROUND-COMPLETION `computeNextTier` treats `tier = None`
  // as Core (a peer in roundStart with no prior classification was floor-promoted into
  // Core for that round). At COMMITTEE-DERIVATION `CommitteeBuilder` defaults an unknown
  // peer to Tier 1 unless `peerQuality` proves Core -- derivation gates Core entry on
  // demonstrated participation. Do not conflate the two.
  //
  // MUST be `Option[Int]`, not `Int = 0`: same derevo-decoder caveat as
  // `viewChangesCaused` above. Wrapping in Option makes the field truly optional at
  // decode time; with `Printer(dropNullValues = true)` in production, `None` is dropped
  // from JSON entirely so v19-encoded snapshots are byte-identical to pre-v19 for
  // peers that have not yet been classified.
  //
  // NOT in deterministicConfigHash: the tier value is per-peer-derived, not a
  // cluster-wide config knob. Operators do not configure tiers; the round-finalize
  // pure function computes them.
  tier: Option[Int] = None,
  // Bounded integral controller score for admitting peers into rewards-affecting
  // active consensus roles. Updated only from finalized, consensus-agreed evidence
  // (completed signer, accepted responder set, evictions, and finalized self-health
  // hints) and persisted so restarts do not reset a peer from no-evidence to active.
  //
  // MUST remain optional for the same derevo back-compat reason as viewChangesCaused:
  // snapshots written before this controller existed have no key here.
  activeAdmissionScore: Option[Int] = None
)

object PerPeerOperationalRecord {
  val empty: PerPeerOperationalRecord =
    PerPeerOperationalRecord(
      quality = (0, 0),
      removalPenalty = 0,
      cumulativeMissCount = 0L,
      readmissionCountdown = 0,
      deferralCountdown = 0,
      viewChangesCaused = None,
      tier = None,
      activeAdmissionScore = None
    )
}

// Persisted snapshot of consensus-derived peer-behavior counters.
//
// Carried as an optional field on the incremental snapshot (initially `None`
// for back-compat with pre-v20 snapshots in storage). The contents are
// populated by packing the corresponding fields from the previous round's
// `GlobalConsensusOutcome` / `CurrencyConsensusOutcome` -- both of which are
// consensus-agreed, so every facilitator places the same value into its
// proposal and `validateArtifact` re-execution converges byte-identically.
//
// Determinism contract: every entry here MUST be a deterministic function of
// the previous round's outcome. Do not introduce wall-clock, randomness, or
// node-local state into the pack/unpack helpers.
//
// Known off-by-one on cold-restart: snapshot[N].peerHistory packs the outcome
// AS-OF round N proposal time (= pack(Outcome[N-1])), because the snapshot's
// signed bytes are sealed at proposal-build before Outcome[N] exists. On
// rollback to N, we therefore seed `state.lastOutcome` with N-1 era counters
// rather than N era counters -- losing exactly one round of evolution per
// cold-restart. That 1-round drift on `cumulativeMissCount` etc. is well below
// the chronic-classifier floor (10-30 observations) and is not material vs the
// alternative (no persistence at all).
//
// `perPeer` holds the five PeerId-keyed dimensions. `recentProofSizes` is
// keyed by SnapshotOrdinal so it stays separate.
//
// `recentSigners` is the per-ordinal signer set for the last K successful
// outcomes, used by FacilitatorSelector to narrow the next-round committee to
// peers who have signed M of the last K outcomes. MUST be Option-wrapped
// because the derevo-derived JSON decoder treats Scala defaults as required
// keys: a snapshot written before the field existed would have peerHistory
// containing only {perPeer, recentProofSizes} and would fail decode if
// `recentSigners` were a plain SortedMap with default empty. With Option +
// dropNullValues=true, older snapshots decode None and readers treat absent
// as bootstrap (window-not-yet-full). Same migration pattern as the Option
// wrap on `viewChangesCaused` in PerPeerOperationalRecord above.
@derive(eqv, show, encoder, decoder)
final case class ConsensusOperationalState(
  perPeer: SortedMap[PeerId, PerPeerOperationalRecord],
  recentProofSizes: SortedMap[SnapshotOrdinal, Int],
  recentSigners: Option[SortedMap[SnapshotOrdinal, SortedSet[PeerId]]] = None,
  // v19 phase 2 view-from-time anchor: per-ordinal canonical `consensusEndTime`
  // derived from the median of `Facility.proposerClockMs` values agreed in the round
  // and clamped against the parent's value (Bitcoin MTP-style anti-regression). All
  // facilitators reading the same Facility set converge byte-identically, so the
  // result is consensus-agreed and safe to persist. Consumed by the next round's
  // `view_in_progress = floor((local_now - parent.consensusEndTime) / viewInterval)`
  // view-from-time mechanism (replacing pure vote-driven view derivation).
  //
  // Bounded window: same K as `recentSigners` (`tighteningWindow`). On finalize, append
  // `(ordinal -> consensusEndTime)` and prune outside the window.
  //
  // MUST be Option-wrapped: same derevo-decoder caveat as `recentSigners` above. Older
  // snapshots have no key under `peerHistory`; with `dropNullValues=true` an Option(None)
  // is dropped from JSON entirely and byte-stable encoding for pre-v19 snapshots is
  // preserved. `None` at read time means the window has not yet been populated (bootstrap
  // / rollback to a pre-v19 snapshot / partial-deploy with <n/2+1 facilities carrying
  // proposerClockMs) and the round continues to derive view from `viewChangeVotes.maxToView`
  // (phase 1) until the median becomes computable across the cluster.
  recentRoundEndTimes: Option[SortedMap[SnapshotOrdinal, Long]] = None,
  // Controller evidence stage 4: bounded window of (ordinal -> ControllerEvidenceEntry)
  // recording the canonical facts of each finalized round (round-start committee,
  // completed signer set, certified timeout voters, certified admissions, certified
  // evictions). Persisting it here lets a cold restart re-seed the outcome's
  // `controllerEvidence` window from the sidecar / `snapshot.peerHistory`, so the
  // evidence-derived controller state (scores / tiers / quality via
  // `ControllerEvidenceDerivation`) survives the restart boundary as a pure function of
  // signed chain facts -- the replacement for the locally-divergent carried-map seeds
  // behind the alpha.92/129/147 wedges.
  //
  // MUST be Option-wrapped: same derevo-decoder caveat as `recentSigners` above. Older
  // snapshots have no key under `peerHistory`; with `dropNullValues=true` a `None` is
  // dropped from JSON entirely so pre-deploy encodings stay byte-stable. `None` at read
  // time means the evidence window is empty (bootstrap / pre-deploy snapshot) and
  // consumers fall back to the carried maps until the window fills.
  controllerEvidence: Option[SortedMap[SnapshotOrdinal, ControllerEvidenceEntry]] = None,
  // Controller evidence stage 4: cert-anchored absolute penalty horizon per peer. An
  // EvictionCertificate applied at ordinal N writes `target -> N + penaltyDurationOrdinals`;
  // an AdmissionCertificate clears the entry; expired entries are dropped at finalization.
  // Persisted so penalties survive cold-restart as pure ordinal comparisons -- no per-round
  // countdown a restart could observe half-decremented.
  //
  // MUST be Option-wrapped: same derevo-decoder back-compat caveat as `controllerEvidence`.
  penaltyUntil: Option[SortedMap[PeerId, SnapshotOrdinal]] = None
)

object ConsensusOperationalState {
  val empty: ConsensusOperationalState =
    ConsensusOperationalState(
      perPeer = SortedMap.empty,
      recentProofSizes = SortedMap.empty,
      recentSigners = None,
      recentRoundEndTimes = None,
      controllerEvidence = None,
      penaltyUntil = None
    )
}
