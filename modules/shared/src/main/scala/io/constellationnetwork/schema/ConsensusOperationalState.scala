package io.constellationnetwork.schema

import scala.collection.immutable.SortedMap

import io.constellationnetwork.schema.peer.PeerId

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

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
// On rollback (Main.scala consumes via `seed`), the six fields are spliced
// into the GlobalConsensusOutcome that seeds startFacilitatingAfterRollback,
// so chronic-classifier history, B2 readmission, and removal-penalty escalation
// survive cluster cold-starts without re-bootstrapping from zero.
@derive(eqv, show, encoder, decoder)
final case class ConsensusOperationalState(
  peerQuality: SortedMap[PeerId, (Int, Int)],
  removalPenalties: SortedMap[PeerId, Int],
  cumulativeMissCounts: SortedMap[PeerId, Long],
  readmissionCountdown: SortedMap[PeerId, Int],
  recentProofSizes: SortedMap[SnapshotOrdinal, Int],
  deferralCountdown: SortedMap[PeerId, Int]
)

object ConsensusOperationalState {
  val empty: ConsensusOperationalState =
    ConsensusOperationalState(
      peerQuality = SortedMap.empty,
      removalPenalties = SortedMap.empty,
      cumulativeMissCounts = SortedMap.empty,
      readmissionCountdown = SortedMap.empty,
      recentProofSizes = SortedMap.empty,
      deferralCountdown = SortedMap.empty
    )
}
