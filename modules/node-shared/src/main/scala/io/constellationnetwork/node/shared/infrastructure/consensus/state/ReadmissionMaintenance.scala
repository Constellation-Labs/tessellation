package io.constellationnetwork.node.shared.infrastructure.consensus.state

import scala.collection.immutable.SortedMap

import io.constellationnetwork.schema.peer.PeerId

/** Per-round maintenance of `readmissionCountdown` -- the B2 probation map.
  *
  * Sticky-probation: a peer's countdown decrements every round but **clamps at 0** instead of auto-clearing the entry. The only path that
  * removes a peer from probation is an accepted `AdmissionCertificate` (passed in here as `admittedThisRound`).
  *
  * Earlier code used `.filter(_._2 > 0)` here, which dropped the entry when the countdown ran out. Empirical motivation: alpha.50 produced
  * ZERO admission certs in 14 hours, because the StallDetector emission gate (probation intersect atTip intersect consecutive-streak) only
  * considers peers still in the probation set, but those peers exited probation via auto-clear before the streak threshold could fire.
  *
  * Extracted as a pure function so the semantic shift is unit-testable independent of the full advancer.
  */
object ReadmissionMaintenance {

  /** Apply one round of probation maintenance.
    *
    *   1. Decrement every active counter by 1, clamped at 0. 2. Seed entries for peers in `justUnpenalized` at `probationRounds` (only if
    *      not already present). 3. Remove entries for peers in `admittedThisRound` (the only path out of probation).
    *
    * Invariant: `admittedThisRound ∩ justUnpenalized = ∅` holds by protocol timing — a peer enters `readmissionCountdown` (seeded from
    * `justUnpenalized`) in round N, and an `AdmissionCertificate` for that peer requires multiple rounds of B2 vote accumulation, so it
    * cannot be assembled and admitted in the same round N. There is no explicit upstream guard; if both sets ever overlap (e.g. a future
    * caller breaks the timing assumption), the admit step wins (entry removed) because `-- admittedThisRound` applies last.
    */
  def step(
    prev: SortedMap[PeerId, Int],
    justUnpenalized: Set[PeerId],
    admittedThisRound: Set[PeerId],
    probationRounds: Int
  ): SortedMap[PeerId, Int] = {
    val decremented =
      prev.view.mapValues(c => math.max(0, c - 1)).to(SortedMap)
    val seeded =
      if (probationRounds <= 0) decremented
      else
        justUnpenalized.foldLeft(decremented) { (acc, pid) =>
          if (!acc.contains(pid)) acc.updated(pid, probationRounds) else acc
        }
    (seeded -- admittedThisRound).to(SortedMap)
  }
}
