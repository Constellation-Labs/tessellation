package io.constellationnetwork.node.shared.infrastructure.consensus.state

import io.constellationnetwork.schema.peer.PeerId

/** Deterministic witness pool for B1 / B2 / VCC certificate assembly and validation.
  *
  * ==Why this exists==
  *
  * In the canonical "committee = signers of previous snapshot" pattern, when a supermajority of the committee is offline or stuck in
  * `WaitingForDownload`, the round can't progress AND the eviction / admission / view-change certificate that would normally rotate the
  * committee also can't assemble (it gates on the same supermajority of the same committee). Letting peers with proven prior participation
  * witness the cert -- without giving them a vote in the round itself -- breaks the deadlock without weakening the round's BFT guarantee.
  * The quorum denominator stays committee-sized; only the set of valid witness signers widens.
  *
  * ==Determinism contract==
  *
  * Every honest node MUST compute the byte-identical pool when validating an incoming cert as the leader did when assembling it. The two
  * inputs that govern the pool are therefore restricted to consensus-agreed sources:
  *
  *   - `eligibleFacilitators`: derived from the previous outcome (signed in the snapshot) via the chronic-classifier. Same for every node
  *     that observes the previous snapshot.
  *   - `peerQuality`: signed-in-snapshot per-peer `(completed, participated)` counters from `lastOutcome.peerQuality`. Same for every node
  *     that observes the previous snapshot.
  *   - `minParticipationObservations`: lives in `ConsensusConfig.deterministicConfigHash`, so a deploy with a divergent value rejects peer
  *     connections at the version gate.
  *
  * The result is a `Set[PeerId]` -- order-independent -- removing iteration order as a determinism concern. Downstream cert builders sort
  * the resulting votes into a `SortedSet` so the on-the-wire serialization is stable.
  *
  * ==Monotonicity==
  *
  * `peerQuality` grows monotonically: entries are added, counters increment; the map does not arbitrarily reshape. The wider pool is
  * therefore a monotone function of round history. In steady state with a healthy committee, the union is dominated by
  * `eligibleFacilitators`; the wider members only matter when the committee is degraded.
  */
object WitnessPool {

  /** Compute the witness pool for a cert keyed by a target peer (B1 eviction, B2 admission).
    *
    *   - Union of `eligibleFacilitators` and historical participants from `peerQuality`.
    *   - A historical participant is any peer whose `participated >= minParticipationObservations` in the carried `peerQuality`.
    *   - `target` is excluded so a peer cannot witness its own eviction / admission.
    */
  def forTarget(
    eligibleFacilitators: Set[PeerId],
    peerQuality: Map[PeerId, (Int, Int)],
    minParticipationObservations: Int,
    target: PeerId
  ): Set[PeerId] =
    all(eligibleFacilitators, peerQuality, minParticipationObservations) - target

  /** Compute the witness pool for a cert that is not keyed by a single target (VCC view change). */
  def all(
    eligibleFacilitators: Set[PeerId],
    peerQuality: Map[PeerId, (Int, Int)],
    minParticipationObservations: Int
  ): Set[PeerId] = {
    val historicalParticipants = peerQuality.collect {
      case (pid, (_, participated)) if participated >= minParticipationObservations => pid
    }.toSet
    eligibleFacilitators ++ historicalParticipants
  }
}
