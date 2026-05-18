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
  // v16 (2026-05-17): cumulative count of view changes this peer caused as a failed
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
  viewChangesCaused: Option[Long] = None
)

object PerPeerOperationalRecord {
  val empty: PerPeerOperationalRecord =
    PerPeerOperationalRecord(
      quality = (0, 0),
      removalPenalty = 0,
      cumulativeMissCount = 0L,
      readmissionCountdown = 0,
      deferralCountdown = 0,
      viewChangesCaused = None
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
  recentSigners: Option[SortedMap[SnapshotOrdinal, SortedSet[PeerId]]] = None
)

object ConsensusOperationalState {
  val empty: ConsensusOperationalState =
    ConsensusOperationalState(
      perPeer = SortedMap.empty,
      recentProofSizes = SortedMap.empty,
      recentSigners = None
    )
}
