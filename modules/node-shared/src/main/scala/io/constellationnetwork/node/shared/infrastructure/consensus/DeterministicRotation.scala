package io.constellationnetwork.node.shared.infrastructure.consensus

import io.constellationnetwork.schema.peer.PeerId

/** Deterministic, hash-free rotation for admission and audit selection.
  *
  * Candidates are sorted by PeerId and rotated to start at `index mod n`. Each selector derives its index from the round ordinal and the
  * admission cadence (never from a snapshot hash a proposer can grind, and never from a local counter), so honest nodes agree and
  * consecutive opportunities advance one position regardless of how the candidate count and the cadence share factors.
  */
object DeterministicRotation {

  def rotated(peers: Iterable[PeerId], index: Long): List[PeerId] = {
    val sorted = peers.toList.distinct.sorted
    if (sorted.isEmpty) sorted
    else {
      val start = java.lang.Math.floorMod(index, sorted.size.toLong).toInt
      sorted.drop(start) ++ sorted.take(start)
    }
  }

  /** Ordering by position in the rotated sequence of `peers`; peers outside the set sort last by PeerId. */
  def ordering(peers: Iterable[PeerId], index: Long): Ordering[PeerId] = {
    val position = rotated(peers, index).zipWithIndex.toMap
    Ordering.by((pid: PeerId) => (position.getOrElse(pid, Int.MaxValue), pid))
  }

  /** Index of the admission opportunity at `roundOrdinal`. */
  def opportunityIndex(roundOrdinal: Long, expansionIntervalRounds: Int): Long =
    java.lang.Math.floorDiv(roundOrdinal, math.max(1, expansionIntervalRounds).toLong)

  /** A nomination carried by round N serves the opportunity at N + 1. */
  def nominationIndex(roundOrdinal: Long, expansionIntervalRounds: Int): Long =
    opportunityIndex(roundOrdinal + 1L, expansionIntervalRounds)
}
