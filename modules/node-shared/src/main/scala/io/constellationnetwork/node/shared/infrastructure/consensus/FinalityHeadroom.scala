package io.constellationnetwork.node.shared.infrastructure.consensus

import io.constellationnetwork.node.shared.infrastructure.consensus.state.QuorumPolicy
import io.constellationnetwork.schema.peer.PeerId

/** Exact next-seat finality-headroom calculation shared by admission and Tier-1 silence eviction.
  *
  * Finalized proof subsets are node-local, so this result may only control local vote emission. It must never be validated from a Proposal
  * or copied into deterministic state. Admission and eviction remain authoritative only after their existing Core-quorum certificates are
  * accepted.
  */
object FinalityHeadroom {

  final case class Evaluation(
    currentCommitteeSize: Int,
    observedCurrentCommitteeSigners: Int,
    nextCommitteeSize: Int,
    nextFinalityFloor: Int
  ) {
    val margin: Int = observedCurrentCommitteeSigners - nextFinalityFloor
    val allowsExpansion: Boolean = margin >= 0

    /** A silent seat should be removed only when the observed signer population cannot safely support one additional seat. */
    val allowsSilentEviction: Boolean = !allowsExpansion
  }

  /** Evaluate the protocol-derived invariant:
    *
    * {{{
    * observed current-committee parent signers >= finality floor(current committee size + 1)
    * }}}
    */
  def evaluate(
    currentCommittee: Set[PeerId],
    locallyObservedParentSigners: Set[PeerId],
    quorumThresholdFraction: Double
  ): Evaluation = {
    val nextCommitteeSize = currentCommittee.size + 1
    val nextFinalityFloor = math.max(1, QuorumPolicy.fromFraction(nextCommitteeSize, quorumThresholdFraction))

    Evaluation(
      currentCommitteeSize = currentCommittee.size,
      observedCurrentCommitteeSigners = locallyObservedParentSigners.intersect(currentCommittee).size,
      nextCommitteeSize = nextCommitteeSize,
      nextFinalityFloor = nextFinalityFloor
    )
  }
}
