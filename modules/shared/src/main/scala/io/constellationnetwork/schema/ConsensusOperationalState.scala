package io.constellationnetwork.schema

import scala.collection.immutable.SortedMap

import io.constellationnetwork.schema.peer.PeerId

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

/** Per-peer consensus operational state that survives the single-voting-set design: removal penalties and probation (readmission)
  * bookkeeping. No quality, tier, score, view-change blame, or leader-reported participation is carried.
  */
@derive(eqv, show, encoder, decoder)
final case class PerPeerOperationalRecord(
  removalPenalty: Int,
  cumulativeMissCount: Long,
  readmissionCountdown: Int
)

object PerPeerOperationalRecord {
  val empty: PerPeerOperationalRecord =
    PerPeerOperationalRecord(removalPenalty = 0, cumulativeMissCount = 0L, readmissionCountdown = 0)
}

/** Per-member membership-clearing record (ADR-0036 clearing). Counters cover the member's current interval; `lastCounted*` hold the most
  * recent interval in which the member was seated for every round, and `lowStreak` counts consecutive counted intervals with presence below
  * one tenth. A member's record exists only while it is seated; leaving the committee drops it and re-admission starts a fresh one.
  */
@derive(eqv, show, encoder, decoder)
final case class ClearingRecord(
  seatActivationOrdinal: SnapshotOrdinal,
  currentInterval: Long,
  seated: Int,
  present: Int,
  lastCountedInterval: Option[Long],
  lastCountedSeated: Int,
  lastCountedPresent: Int,
  lowStreak: Int
)

/** Committed membership-clearing state. `clearedInterval` is the interval in which the last clearing executed (one per interval). */
@derive(eqv, show, encoder, decoder)
final case class ClearingState(
  members: SortedMap[PeerId, ClearingRecord],
  clearedInterval: Option[Long]
)

object ClearingState {
  val empty: ClearingState = ClearingState(SortedMap.empty, None)
}

/** Post-round operational state. Packed into the signed `peerHistory` of the next artifact (see the dag-l0 packer) and committed by the
  * certified `nextOperationalStateHash`.
  *
  *   - `recentCommitteeSizes`: canonical committee size per recent round.
  *   - `recentRoundEndTimes`: per-round `consensusEndTime` anchors for view-from-time.
  *   - `penaltyUntil`: certificate-anchored absolute penalty horizons.
  *   - `clearing`: membership-clearing counters derived from embedded commit QCs (absent before clearing activation).
  *
  * Option-wrapped fields keep `dropNullValues` encodings stable when a window is empty.
  */
@derive(eqv, show, encoder, decoder)
final case class ConsensusOperationalState(
  perPeer: SortedMap[PeerId, PerPeerOperationalRecord],
  recentCommitteeSizes: SortedMap[SnapshotOrdinal, Int],
  recentRoundEndTimes: Option[SortedMap[SnapshotOrdinal, Long]] = None,
  penaltyUntil: Option[SortedMap[PeerId, SnapshotOrdinal]] = None,
  clearing: Option[ClearingState] = None
)

object ConsensusOperationalState {
  val empty: ConsensusOperationalState =
    ConsensusOperationalState(
      perPeer = SortedMap.empty,
      recentCommitteeSizes = SortedMap.empty,
      recentRoundEndTimes = None,
      penaltyUntil = None
    )
}
