package io.constellationnetwork.node.shared.infrastructure.consensus

/** v19 phase 2 view-from-time anchor: pure helper for deriving the round's initial view number from wall-clock progress since the parent
  * snapshot's `consensusEndTime`.
  *
  * Pairs with [[ConsensusEndTime]] (the producer side): every finalized round writes its median-derived `consensusEndTime` into the
  * outcome's `recentRoundEndTimes` sliding window. At the start of the next round each peer reads the parent's value, subtracts from
  * `local_now`, and divides by `viewInterval` to get a deterministic-ish view number.
  *
  * ==Determinism contract==
  *
  *   - `parentEndTimeMs` is a consensus-agreed signed-outcome field on the L0 layer. Every honest node sees the same value.
  *   - `viewIntervalMs` is gated by `deterministicConfigHash`. Operators with divergent values reject each other at peer connection.
  *   - `nowMs` is the only local input. NTP skew across nodes typically sits at +/- 10ms on AWS-class infra. With a 30s `viewInterval`,
  *     that's 3 parts in 10,000 -- below view-transition resolution. At the boundary itself a peer can briefly disagree by one view; the
  *     existing VCC machinery resolves this within one `viewInterval`.
  *
  * Combined with phase 1 (`priorAbandonmentCount`) via `math.max` at the call site: whichever signal is higher wins. Phase 1 reflects
  * accumulated view-change-vote history; phase 2 reflects wall-clock progress. Neither can backdate the other.
  */
object ViewFromTime {

  /** Derive the round-start view number from elapsed wall-clock time since the parent's `consensusEndTime`.
    *
    * @param nowMs
    *   Caller's wall-clock at round start. Acquired via `Clock[F].realTime.map(_.toMillis)` to match the producer-side
    *   `Facility.proposerClockMs` capture.
    * @param parentEndTimeMs
    *   Last entry from `lastOutcome.recentRoundEndTimes`, or `None` at bootstrap / after a pre-phase-2 rollback. None falls back to 0 so
    *   the call site can rely on phase 1 (`priorAbandonmentCount`) alone.
    * @param viewIntervalMs
    *   Divisor from `config.viewInterval`. Must be positive; a non-positive value would explode the formula. Caller is expected to enforce
    *   this via refined types or config validation.
    * @return
    *   Non-negative `Int`. Negative deltas (clock regression at the consumer side) collapse to 0 -- phase 1 still applies. Overflow at
    *   `Int.MaxValue` (would require a ~50-year stall at 30s interval) saturates rather than wraps.
    */
  def compute(nowMs: Long, parentEndTimeMs: Option[Long], viewIntervalMs: Long): Int =
    parentEndTimeMs.fold(0) { parent =>
      val deltaMs = nowMs - parent
      if (deltaMs <= 0L) 0
      else {
        val rawView = deltaMs / viewIntervalMs
        if (rawView > Int.MaxValue.toLong) Int.MaxValue
        else rawView.toInt
      }
    }
}
