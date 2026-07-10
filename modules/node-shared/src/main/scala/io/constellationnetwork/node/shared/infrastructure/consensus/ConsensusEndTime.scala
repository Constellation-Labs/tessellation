package io.constellationnetwork.node.shared.infrastructure.consensus

import io.constellationnetwork.node.shared.infrastructure.consensus.declaration.Facility

/** v19 phase 2 view-from-time anchor: pure helper for deriving the round's canonical `consensusEndTime` from the accepted Facility set.
  *
  * ==Algorithm==
  *
  *   - Filter Facilities for non-`None` `proposerClockMs`.
  *   - Require at least `floor(n/2) + 1` (strict majority of the round's accepted Facility set) to carry clocks. Below that threshold we
  *     return `None` and the consume site falls back to phase 1 vote-driven view derivation. This keeps a partial-deploy window (some peers
  *     pre-v19, some post) from yielding a non-determinic anchor.
  *   - Compute the integer median (lower median for even counts, deterministic).
  *   - Bitcoin MTP-style clamp: result is `max(median, parentEndTime + 1)`. Ensures monotonic non-decreasing time across rounds even when
  *     proposers' wall-clocks regress.
  *
  * ==Determinism contract==
  *
  * Every input is a consensus-agreed signed Facility (`facilities` is the post-fork-eviction map returned by `maybeGetAllDeclarations`,
  * byte-identical across honest nodes that complete the round). The integer median is order-independent of `SortedMap` iteration.
  * `parentEndTime` is read from the prior outcome's `recentRoundEndTimes`, also consensus agreed. Output is byte-stable across the cluster.
  *
  * ==Why median, why clamp==
  *
  *   - Median absorbs proposer-clock outliers (a single faulty clock cannot drag the anchor) without requiring trimmed-mean or
  *     weighted-median complexity.
  *   - Lower-median for even counts is deterministic and avoids floating-point.
  *   - Clamp against `parentEndTime + 1` enforces monotonicity. Even if every proposer reports a regressed wall-clock, the anchor advances
  *     by at least 1 ms per round.
  *
  * @see
  *   docs/consensus/view-from-time-anchor.md
  */
object ConsensusEndTime {

  /** Compute the round's `consensusEndTime` from the accepted Facility set.
    *
    * @param facilities
    *   The post-fork-eviction Facility set (consensus-agreed across honest nodes).
    * @param parentEndTime
    *   The prior round's `consensusEndTime`, or `None` at bootstrap / on rollback to a pre-v19 snapshot. Used as the clamp lower bound (`>=
    *   parentEndTime + 1`).
    * @return
    *   `Some(endTime)` when the strict-majority threshold of facilities carry `proposerClockMs`; `None` otherwise. `None` means the round
    *   produced no reliable timestamp and the consume site falls back to phase 1 view derivation.
    */
  def compute(facilities: Iterable[Facility], parentEndTime: Option[Long]): Option[Long] = {
    val clocks: List[Long] = facilities.iterator.flatMap(_.proposerClockMs).toList
    val n = facilities.size
    val threshold = (n / 2) + 1
    if (clocks.size < threshold) None
    else {
      val sorted = clocks.sorted
      val medianIdx = clocks.size / 2 // lower-median for even counts (deterministic)
      val median = sorted(medianIdx)
      val clamped = parentEndTime match {
        case Some(p) => math.max(median, p + 1L)
        case None    => median
      }
      Some(clamped)
    }
  }
}
