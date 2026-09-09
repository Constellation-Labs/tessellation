package io.constellationnetwork.node.shared.infrastructure.consensus

/** v19 phase 2 view-from-time anchor: pure helper for deriving a timeout hint from wall-clock progress since the parent snapshot's
  * `consensusEndTime`.
  *
  * Pairs with [[ConsensusEndTime]] (the producer side): every finalized round writes its median-derived `consensusEndTime` into the
  * outcome's `recentRoundEndTimes` sliding window. At the start of the next round each peer reads the parent's value, subtracts from
  * `local_now`, and divides by `viewInterval` to decide whether the pacemaker should emit a signed timeout/view-change vote.
  *
  * ==Determinism contract==
  *
  *   - `parentEndTimeMs` is a consensus-agreed signed-outcome field on the L0 layer. Every honest node sees the same value.
  *   - `viewIntervalMs` is gated by `deterministicConfigHash`. Operators with divergent values reject each other at peer connection.
  *   - `nowMs` is the only local input, so the result is not proposal-critical consensus state. It can wake the pacemaker, but a quorum of
  *     signed VCVs/VCC is still required before accepting proposals at a higher view.
  *
  * Do not feed this value directly into round-start view or leader selection. Doing so lets local clocks split honest peers across
  * different `(fromView, toView)` votes and starves certificate assembly.
  */
object ViewFromTime {

  /** Derive the timeout hint from elapsed wall-clock time since the parent's `consensusEndTime`.
    *
    * @param nowMs
    *   Caller's wall-clock at round start. Acquired via `Clock[F].realTime.map(_.toMillis)` to match the producer-side
    *   `Facility.proposerClockMs` capture.
    * @param parentEndTimeMs
    *   Last entry from `lastOutcome.recentRoundEndTimes`, or `None` at bootstrap / after a pre-phase-2 rollback. None falls back to 0.
    * @param viewIntervalMs
    *   Divisor from `config.viewInterval`. Must be positive; a non-positive value would explode the formula. Caller is expected to enforce
    *   this via refined types or config validation.
    * @return
    *   Non-negative `Int`. Negative deltas (clock regression at the consumer side) collapse to 0. Overflow at `Int.MaxValue` (would require
    *   a ~50-year stall at 30s interval) saturates rather than wraps.
    */
  def compute(nowMs: Long, parentEndTimeMs: Option[Long], viewIntervalMs: Long): Int =
    // Guard the divisor: a non-positive interval (misconfiguration) yields view 0 rather than an
    // ArithmeticException. The interval is expected to be strictly positive; this is defence in depth.
    if (viewIntervalMs <= 0L) 0
    else
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
