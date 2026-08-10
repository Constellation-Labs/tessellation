package io.constellationnetwork.node.shared.infrastructure.consensus

import io.constellationnetwork.node.shared.infrastructure.consensus.state.QuorumPolicy
import io.constellationnetwork.schema.peer.PeerId

/** Local vote-emission policy for open Ready-at-tip admission.
  *
  * Cadence is consensus-configured and may also be enforced when proposals are built and validated. Headroom is deliberately different:
  * finalized proof subsets can differ between honest nodes, so the observed signer count may only decide whether this node emits a vote.
  * Membership still changes exclusively through a quorum-certified AdmissionCertificate.
  */
object OpenAdmissionPolicy {

  /** The five-round cadence applies only to open expansion. A peer in the existing penalty/probation recovery lane remains
    * certificate-eligible on every round.
    */
  def certificateAllowed(target: PeerId, probation: Set[PeerId], openCadenceAllowed: Boolean): Boolean =
    probation.contains(target) || openCadenceAllowed

  /** An active penalty blocks a new/open admission, but not the certified recovery of a peer already in consensus-agreed probation.
    *
    * `penaltyUntil` is cleared only after an accepted AdmissionCertificate. Treating that carried horizon as a blocker for the same
    * probation certificate creates a circular deadlock: the certificate cannot be accepted until the penalty clears, and the penalty cannot
    * clear until the certificate is accepted.
    */
  def penaltyBlocksCertificate(target: PeerId, probation: Set[PeerId], activePenaltyPeers: Set[PeerId]): Boolean =
    activePenaltyPeers.contains(target) && !probation.contains(target)

  final case class Headroom(
    observedCurrentCommitteeSigners: Int,
    nextCommitteeSize: Int,
    nextFinalityFloor: Int
  ) {
    val margin: Int = observedCurrentCommitteeSigners - nextFinalityFloor
    val allowsExpansion: Boolean = margin >= 0
  }

  final case class Decision(cadenceAllowed: Boolean, headroom: Option[Headroom]) {
    val allowsOpenAdmission: Boolean = cadenceAllowed && headroom.forall(_.allowsExpansion)
  }

  /** Evaluate whether this node may emit an open-admission vote for one additional seat.
    *
    * The exact invariant is:
    *
    * {{{
    * observed current-committee parent signers >= finality floor(current committee size + 1)
    * }}}
    *
    * The headroom gate is active only when the full-committee finality floor is active. Global L0 therefore disables it during bootstrap,
    * where finality still uses the legacy Core-only gate and a newly admitted Tier-1 seat does not raise the finality requirement. This
    * also prevents a singleton bootstrap under unanimity from requiring two current signers before it can admit its second seat.
    *
    * `None` also leaves the local headroom gate inactive. Currency L0 uses that path because its unanimity policy could never prove an
    * unseated `(n + 1)`th signer; post-bootstrap Global L0 supplies its locally observed parent proof set.
    */
  def evaluate(
    cadenceAllowed: Boolean,
    currentCommittee: Set[PeerId],
    locallyObservedParentSigners: Option[Set[PeerId]],
    quorumThresholdFraction: Double,
    headroomGateActive: Boolean
  ): Decision = {
    val headroomEvidence = locallyObservedParentSigners.filter(_ => headroomGateActive)
    val headroom = headroomEvidence.fold(Option.empty[Headroom]) { observedSigners =>
      val nextCommitteeSize = currentCommittee.size + 1
      val nextFinalityFloor = math.max(1, QuorumPolicy.fromFraction(nextCommitteeSize, quorumThresholdFraction))
      Some(
        Headroom(
          observedCurrentCommitteeSigners = observedSigners.intersect(currentCommittee).size,
          nextCommitteeSize = nextCommitteeSize,
          nextFinalityFloor = nextFinalityFloor
        )
      )
    }

    Decision(cadenceAllowed, headroom)
  }
}
