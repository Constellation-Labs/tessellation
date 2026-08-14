package io.constellationnetwork.node.shared.infrastructure.consensus

import scala.concurrent.duration.FiniteDuration

import io.constellationnetwork.schema.peer.PeerId

/** Local vote-emission policy for open Ready-at-tip admission.
  *
  * Cadence is consensus-configured and may also be enforced when proposals are built and validated. Headroom is deliberately different:
  * finalized proof subsets can differ between honest nodes, so the observed signer count may only decide whether this node emits a vote.
  * Membership still changes exclusively through a quorum-certified AdmissionCertificate.
  */
object OpenAdmissionPolicy {

  final case class PreProposalGraceDecision(
    effectiveGrace: FiniteDuration,
    hasAdmissionEvidence: Boolean,
    shouldWait: Boolean
  )

  /** Bounded local wait for admission-certificate assembly before proposal construction.
    *
    * Probation presence is evidence before the first vote exists. Without that rule, a fast facilities phase can close before a carried
    * probation peer has accumulated its required fresh observations. The recovery window includes one extra attempt interval for
    * signed-vote gossip and certificate assembly. This policy only delays local proposal construction; it is not serialized or used to
    * validate a proposal.
    */
  def preProposalGrace(
    elapsed: FiniteDuration,
    baseGrace: FiniteDuration,
    maxAdmissionSeats: Int,
    probationPresent: Boolean,
    hasOpenEvidence: Boolean,
    hasAdmissionVoteEvidence: Boolean,
    hasApplicableCertificate: Boolean,
    requiredProbationObservations: Int,
    probationProbeInterval: FiniteDuration,
    probationProbeTimeout: FiniteDuration
  ): PreProposalGraceDecision = {
    val perAttemptWindow =
      if (probationProbeInterval >= probationProbeTimeout) probationProbeInterval else probationProbeTimeout
    val requiredAttempts = math.max(1, requiredProbationObservations)
    val probationGrace = perAttemptWindow * (requiredAttempts.toLong + 1L)
    // `elapsed` begins at state creation, before the monitor is guaranteed to be running. Keep
    // the inherited base grace as scheduling allowance, then add the probe/assembly window. Using
    // max(base, probationGrace) would let state-creation work consume the first probe interval.
    val effectiveGrace =
      if (probationPresent) baseGrace + probationGrace else baseGrace
    val hasAdmissionEvidence = probationPresent || hasOpenEvidence || hasAdmissionVoteEvidence
    val shouldWait =
      maxAdmissionSeats > 0 &&
        hasAdmissionEvidence &&
        !hasApplicableCertificate &&
        elapsed < effectiveGrace

    PreProposalGraceDecision(effectiveGrace, hasAdmissionEvidence, shouldWait)
  }

  /** Keep singleton bootstrap able to grow, but enforce headroom on the admission batch that reaches the proof threshold where
    * full-committee finality can activate. Without this edge gate, one signer could admit a third seat and immediately create a
    * two-signature floor.
    */
  def headroomRequired(
    certifiedConsensusActive: Boolean,
    bootstrapActive: Boolean,
    currentCommitteeSize: Int,
    maxAdmissionSeats: Int,
    bootstrapCompleteProofsThreshold: Int
  ): Boolean = {
    val batchSize = math.max(1, maxAdmissionSeats)
    val threshold = math.max(1, bootstrapCompleteProofsThreshold)
    certifiedConsensusActive || !bootstrapActive || currentCommitteeSize + batchSize >= threshold
  }

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

  final case class Decision(cadenceAllowed: Boolean, headroom: Option[FinalityHeadroom.Evaluation]) {
    val allowsProbationAdmission: Boolean = headroom.forall(_.allowsExpansion)
    val allowsOpenAdmission: Boolean = cadenceAllowed && allowsProbationAdmission
  }

  /** Evaluate whether this node may emit any admission vote for the proposal's maximum admission batch.
    *
    * The exact invariant is:
    *
    * {{{
    * observed current-committee parent signers >= finality floor(current committee size + max admission seats)
    * }}}
    *
    * The headroom gate is active only when the full-committee finality floor is active. Legacy Global L0 therefore disables it during
    * bootstrap, where finality still uses the Core-only gate and a newly admitted Tier-1 seat does not raise the requirement. V35 always
    * uses the frozen full-committee floor, including immediately after its activation reset, so it must keep this gate active even while
    * the legacy proof-size window still reports bootstrap. This also preserves legacy singleton growth without allowing a v35 singleton to
    * admit a second seat it cannot finalize with.
    *
    * Cadence is deliberately applied only to open expansion. Probation recovery uses the same headroom result on every round.
    *
    * `None` also leaves the local headroom gate inactive. Currency L0 uses that path because its unanimity policy could never prove an
    * unseated `(n + 1)`th signer; post-bootstrap Global L0 supplies its locally observed parent proof set.
    */
  def evaluate(
    cadenceAllowed: Boolean,
    currentCommittee: Set[PeerId],
    locallyObservedParentSigners: Option[Set[PeerId]],
    quorumThresholdFraction: Double,
    headroomGateActive: Boolean,
    maxAdmissionSeats: Int = 1
  ): Decision = {
    val headroomEvidence = locallyObservedParentSigners.filter(_ => headroomGateActive)
    val headroom = headroomEvidence.fold(Option.empty[FinalityHeadroom.Evaluation]) { observedSigners =>
      Some(FinalityHeadroom.evaluate(currentCommittee, observedSigners, quorumThresholdFraction, maxAdmissionSeats))
    }

    Decision(cadenceAllowed, headroom)
  }
}
