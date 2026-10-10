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

/** Post-round operational state. Packed into the signed `peerHistory` of the next artifact (see the dag-l0 packer) and committed by the
  * certified `nextOperationalStateHash`.
  *
  *   - `recentProofSizes`: canonical committee size per recent round.
  *   - `recentRoundEndTimes`: per-round `consensusEndTime` anchors for view-from-time.
  *   - `penaltyUntil`: certificate-anchored absolute penalty horizons.
  *
  * Option-wrapped fields keep `dropNullValues` encodings stable when a window is empty.
  */
@derive(eqv, show, encoder, decoder)
final case class ConsensusOperationalState(
  perPeer: SortedMap[PeerId, PerPeerOperationalRecord],
  recentProofSizes: SortedMap[SnapshotOrdinal, Int],
  recentRoundEndTimes: Option[SortedMap[SnapshotOrdinal, Long]] = None,
  penaltyUntil: Option[SortedMap[PeerId, SnapshotOrdinal]] = None
)

object ConsensusOperationalState {
  val empty: ConsensusOperationalState =
    ConsensusOperationalState(
      perPeer = SortedMap.empty,
      recentProofSizes = SortedMap.empty,
      recentRoundEndTimes = None,
      penaltyUntil = None
    )
}
