package io.constellationnetwork.node.shared.infrastructure.consensus

import io.constellationnetwork.node.shared.infrastructure.consensus.state.QuorumPolicy
import io.constellationnetwork.schema.peer.PeerId

/** Exact current-seat and next-seat finality-headroom calculation shared by admission and Tier-1 silence eviction.
  *
  * Finalized proof subsets are node-local, so this result may only control local vote emission. It must never be validated from a Proposal
  * or copied into deterministic state. Admission and eviction remain authoritative only after their existing quorum-certified evidence is
  * accepted; open admission uses Core voters while probation recovery preserves its wider witness pool at a Core-sized threshold.
  */
object FinalityHeadroom {

  final case class Evaluation(
    currentCommitteeSize: Int,
    observedCurrentCommitteeSigners: Int,
    currentFinalityFloor: Int,
    nextCommitteeSize: Int,
    nextFinalityFloor: Int
  ) {
    val currentMargin: Int = observedCurrentCommitteeSigners - currentFinalityFloor
    val nextSeatMargin: Int = observedCurrentCommitteeSigners - nextFinalityFloor

    // Retain the original accessor for admission callers and dashboards. It is the
    // next-seat margin, not the current-committee margin.
    val margin: Int = nextSeatMargin

    val allowsExpansion: Boolean = nextSeatMargin >= 0

    /** Remove a silent seat only when the observed signer population is below the floor of the committee already seated.
      *
      * This deliberately leaves a protocol-derived neutral zone when the current committee can finalize but the next seat is not yet
      * supported:
      *
      * {{{
      * currentFloor <= observedSigners < nextFloor
      * }}}
      *
      * Neither admission nor silent eviction is allowed in that zone. Using `!allowsExpansion` here would make adjacent floor steps
      * oscillate between admitting and evicting the same seat.
      */
    val allowsSilentEviction: Boolean = currentMargin < 0

    val holdsMembership: Boolean = !allowsExpansion && !allowsSilentEviction

    /** The old committee can still finalize, but adding a seat would immediately raise the floor beyond the observed signer population. A
      * v35 one-for-one replacement is safe in this dead band because it leaves both committee size and finality floor unchanged.
      */
    val allowsAtomicReplacement: Boolean = currentMargin >= 0 && !allowsExpansion
  }

  /** Evaluate the protocol-derived invariant for the largest admission batch the proposal may carry:
    *
    * {{{
    * observed current-committee parent signers >= finality floor(current committee size + additional seats)
    * }}}
    */
  def evaluate(
    currentCommittee: Set[PeerId],
    locallyObservedParentSigners: Set[PeerId],
    quorumThresholdFraction: Double,
    additionalSeats: Int = 1
  ): Evaluation = {
    val currentCommitteeSize = currentCommittee.size
    val currentFinalityFloor = math.max(1, QuorumPolicy.fromFraction(currentCommitteeSize, quorumThresholdFraction))
    val nextCommitteeSize = currentCommittee.size + math.max(1, additionalSeats)
    val nextFinalityFloor = math.max(1, QuorumPolicy.fromFraction(nextCommitteeSize, quorumThresholdFraction))

    Evaluation(
      currentCommitteeSize = currentCommitteeSize,
      observedCurrentCommitteeSigners = locallyObservedParentSigners.intersect(currentCommittee).size,
      currentFinalityFloor = currentFinalityFloor,
      nextCommitteeSize = nextCommitteeSize,
      nextFinalityFloor = nextFinalityFloor
    )
  }
}
