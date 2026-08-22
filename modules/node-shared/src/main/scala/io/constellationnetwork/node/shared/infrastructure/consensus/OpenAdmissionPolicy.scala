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

  /** A certified eviction is the causal start of atomic-replacement admission work. Measuring this wait from state creation can make the
    * grace already expired when ECS quorum forms, so give the paired ACS one bounded base-grace window from first ECS assembly.
    */
  def atomicPairGraceShouldWait(
    now: FiniteDuration,
    firstEvictionCertificateSeenAt: Option[FiniteDuration],
    baseGrace: FiniteDuration,
    requiresPair: Boolean,
    hasApplicableAdmissionCertificate: Boolean
  ): Boolean =
    requiresPair &&
      !hasApplicableAdmissionCertificate &&
      firstEvictionCertificateSeenAt.exists(firstSeen => now - firstSeen < baseGrace)

  /** Bounded local wait for admission-certificate assembly before proposal construction.
    *
    * Probation presence is evidence before the first vote exists. Without that rule, a fast facilities phase can close before a carried
    * probation peer has accumulated its required fresh observations. The recovery window includes one extra attempt interval for
    * signed-vote gossip and certificate assembly.
    *
    * Open admission needs the same lifecycle allowance even though it requires only one fresh observation: the fixed nominee must first
    * deliver a current-round Facility, then answer the direct exact-tip probe, and only then can Core emit and assemble signed votes. At a
    * small committee those stages can begin near the end of the inherited base grace. Reserve one interval for the probe and one for vote
    * gossip/assembly whenever an open nominee or current-key vote exists. The two lanes run concurrently, so probation's longer window
    * subsumes the open window when both are active.
    *
    * This policy only delays local proposal construction; it is not serialized or used to validate a proposal.
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
    val openAdmissionGrace = perAttemptWindow * 2L
    val openAdmissionPipelineActive = hasOpenEvidence || hasAdmissionVoteEvidence
    // `elapsed` begins at state creation, before the monitor is guaranteed to be running. Keep
    // the inherited base grace as scheduling allowance, then add the probe/assembly window. Using
    // max(base, probationGrace) would let state-creation work consume the first probe interval.
    val effectiveGrace =
      if (probationPresent) baseGrace + probationGrace
      else if (openAdmissionPipelineActive) baseGrace + openAdmissionGrace
      else baseGrace
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
    allowSingletonBootstrapExpansion: Boolean,
    bootstrapActive: Boolean,
    currentCommitteeSize: Int,
    maxAdmissionSeats: Int,
    bootstrapCompleteProofsThreshold: Int
  ): Boolean = {
    val batchSize = math.max(1, maxAdmissionSeats)
    val threshold = math.max(1, bootstrapCompleteProofsThreshold)
    val exactSingletonExpansion =
      allowSingletonBootstrapExpansion && currentCommitteeSize == 1 && batchSize == 1

    !exactSingletonExpansion &&
    (certifiedConsensusActive || !bootstrapActive || currentCommitteeSize + batchSize >= threshold)
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

  final case class Decision(
    cadenceAllowed: Boolean,
    headroom: Option[FinalityHeadroom.Evaluation],
    sustainedHeadroom: Option[AdmissionProofHistory.Evaluation]
  ) {
    private val allowsSustainedFloorStep: Boolean = sustainedHeadroom.forall(_.allowsAdmission)

    // Probation remains a separate recovery lane for cadence, probing, and penalty handling. It
    // cannot bypass a floor-raising batch: that would recreate the same grow-then-wedge failure as
    // open admission. Floor-neutral recovery remains immediate because allowsAdmission is true.
    val allowsProbationAdmission: Boolean = headroom.forall(_.allowsExpansion) && allowsSustainedFloorStep
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
    * Global L0 bypasses headroom only while a bootstrap admission batch remains below the proof threshold. The batch that reaches that
    * threshold is gated because it can activate full-committee finality immediately. This still lets a singleton grow without requiring an
    * unseated second signer before the crossing batch. V35 normally uses the frozen full-committee floor, including immediately after an
    * ordinal-gated activation reset, so its headroom gate remains active even while the legacy proof-size window still reports bootstrap.
    * The one exception is the exact first 1 -> 2 transition of a lineage configured to use certified consensus from its canonical first
    * incremental genesis root: that root has no mature committee to preserve, and applying the `(n + 1)` floor would require the unseated
    * second peer to have signed before admission. A monotonic certified fact permanently closes the exception after any two-seat committee
    * has existed; later degradation back to one signer cannot re-arm it. The exception is also limited to a one-seat batch.
    *
    * A batch that raises the finality floor additionally requires the same exact headroom on three consecutive finalized parents. That
    * evidence is a bounded node-local history of actual snapshot proofs, so it remains vote-emission-only just like the one-parent check.
    * Floor-neutral batches do not wait for history because the added seat is not immediately necessary for finality.
    *
    * Cadence is deliberately applied only to open expansion. Probation recovery evaluates on every round, but shares both denominator
    * safety gates: exempting a floor-raising probation seat would recreate the same grow-then-wedge failure through the recovery lane.
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
    maxAdmissionSeats: Int = 1,
    // `None` means this layer has no node-local proof-history gate (Currency L0). Global L0
    // supplies `Some(History.empty)` after restart so an incomplete history fails closed only for
    // a batch that raises the finality floor.
    locallyObservedParentProofHistory: Option[AdmissionProofHistory.History] = None
  ): Decision = {
    val headroomEvidence = locallyObservedParentSigners.filter(_ => headroomGateActive)
    val headroom = headroomEvidence.fold(Option.empty[FinalityHeadroom.Evaluation]) { observedSigners =>
      Some(FinalityHeadroom.evaluate(currentCommittee, observedSigners, quorumThresholdFraction, maxAdmissionSeats))
    }
    val sustainedHeadroom =
      locallyObservedParentProofHistory
        .filter(_ => headroomEvidence.nonEmpty)
        .map(
          AdmissionProofHistory.evaluate(
            _,
            currentCommittee,
            quorumThresholdFraction,
            maxAdmissionSeats
          )
        )

    Decision(cadenceAllowed, headroom, sustainedHeadroom)
  }
}
