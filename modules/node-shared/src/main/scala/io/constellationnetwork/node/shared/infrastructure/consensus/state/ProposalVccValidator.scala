package io.constellationnetwork.node.shared.infrastructure.consensus.state

import cats.syntax.eq._
import cats.syntax.foldable._
import cats.syntax.show._

import io.constellationnetwork.node.shared.infrastructure.consensus.declaration.{TimeoutCertificate, ViewChangeCertificate}
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash

/** Pure helper for the view-change-certificate (VCC) invariants that gate acceptance of a Proposal.
  *
  * Shared between dag-l0's `GlobalSnapshotConsensusStateAdvancer` and currency-l0's `CurrencySnapshotConsensusStateAdvancer` to avoid drift
  * between the two validate paths (see `feedback_share_logic_no_drift` -- consensus-adjacent logic must not be replicated). Both modules
  * call `ProposalVccValidator.validate` from their `validateProposalVcc` closures.
  *
  * Inputs are the deconstructed slice of `ConsensusState` + `Proposal` + `ConsensusConfig` needed for the check -- no IO, no closure
  * dependencies -- so unit tests can drive every branch directly. See `ProposalVccValidatorSuite` for the canonical positive/negative
  * coverage.
  *
  * '''Branch summary''':
  *   - `proposalView == 0` && Some(vcc/tc): reject `view0_proposal_must_not_carry_view_cert`
  *   - `proposalView == 0` && None: accept
  *   - `proposalView > 0` && None && `coreSize <= 1`: accept (alpha.89 solo-core bypass)
  *   - `proposalView > 0` && None && `proposalView == initialViewNumber`: accept (round-start certified seed bypass)
  *   - `proposalView > 0` && None: reject `view{N}_proposal_missing_view_cert`
  *   - `proposalView > 0` && Some(vcc) && Some(tc): reject `view{N}_proposal_multiple_view_certs`
  *   - `proposalView > 0` && Some(vcc) && `vcc.toView != proposalView`: reject `vcc_view_mismatch` (alpha.90 issue 2 -- closes the latent
  *     gap that the alpha.90 seed-view bypass would otherwise expose: a stale 0->1 VCC could be embedded on a view=2 proposal without this
  *     check)
  *   - `vcc.votes.size < quorum`: reject `vcc_under_quorum`
  *   - `vcc.facilitatorsHash =!= facilitatorsHash`: reject `vcc_facilitators_mismatch`
  *   - VCC votes do not all carry the current parent hash: reject `vcc_last_snapshot_mismatch`
  *   - any VCC voter outside the wider witness pool: reject `vcc_voter_not_in_pool`
  *   - `vcc.highestQcInVcc` exists and disagrees with `proposalHash`: reject `highest_qc_carry_forward_violation`
  *   - TC checks mirror the VCC checks and carry `tc_` rejection prefixes. TC highest-QC votes must also agree at their highest view.
  *   - otherwise accept
  *
  * Rejection-code prefixes (`view0_`, `view{N}_`, `vcc_`, `highest_qc_`) are stable -- operator dashboards and gh log greps pivot on these.
  * Do not rename without updating the dashboards.
  */
object ProposalVccValidator {

  /** @param proposalView
    *   `Proposal.view`
    * @param proposalHash
    *   `Proposal.hash`
    * @param proposalVcc
    *   `Proposal.vcc`
    * @param initialViewNumber
    *   `ConsensusState.initialViewNumber` -- the round-start view stamped by the state creator. A round that starts at a non-zero certified
    *   seed view may need to accept a no-VCC proposal at that seed view if the local VCC cache is not rehydrated yet.
    * @param coreSize
    *   `ConsensusState.coreFacilitators.value.size` -- the LIVENESS-quorum denominator (Tier 0 / Core).
    * @param facilitatorsHash
    *   the round's facilitators hash as held by the validator (status-side).
    * @param lastSnapshotHash
    *   the current parent snapshot hash as held by the validator.
    * @param eligibleFacilitators
    *   `ConsensusState.eligibleFacilitators.value.toSet` -- the eligibility set used to compute the wider VCC witness pool.
    * @param peerQuality
    *   `ConsensusState.lastOutcome.peerQuality.toMap` -- historical (completed, participated) counters that widen the pool to long-running
    *   participants.
    * @param quorumThresholdFraction
    *   `ConsensusConfig.quorumThresholdFraction`.
    * @param minParticipationObservations
    *   `ConsensusConfig.minParticipationObservations`.
    * @param quorumShrink
    *   v33 escalating quorum-denominator shrink decision (see `QuorumDenominatorShrink`). When active, the `vcc_under_quorum` /
    *   `tc_under_quorum` gates additionally accept `requiredQuorum` votes FROM THE ANCHOR SET. CRITICAL determinism contract: the decision
    *   passed here must be derived only from consensus-agreed data + the shared time anchor (the
    *   `ConsensusStateAdvancer.quorumShrinkDecision` derivation), NEVER from local retry counters -- a follower whose local counters lag
    *   the assembler's must still accept the shrunken cert, otherwise the recovery proposal is rejected and the wedge persists (the
    *   alpha.92 stale-proposal-rejection shape). `None` preserves pre-v33 behavior byte-identically.
    */
  def validate(
    proposalView: Long,
    proposalHash: Hash,
    proposalVcc: Option[ViewChangeCertificate],
    proposalTimeoutCertificate: Option[TimeoutCertificate] = None,
    initialViewNumber: Int,
    coreSize: Int,
    facilitatorsHash: Hash,
    lastSnapshotHash: Hash,
    eligibleFacilitators: Set[PeerId],
    roundStartFacilitators: Set[PeerId],
    peerQuality: Map[PeerId, (Int, Int)],
    quorumThresholdFraction: Double,
    minParticipationObservations: Int,
    quorumShrink: Option[QuorumDenominatorShrink.Decision] = None
  ): Either[ProposalRejection, Unit] = {
    val n = coreSize
    // Integer supermajority via shared `QuorumPolicy.fromFraction`. The legacy `ceil(n * fraction)`
    // math is preserved here (rather than switching to `supermajority(n)`) because the validator
    // API still accepts a configurable fraction so dev unanimity (1.0) and testnet supermajority
    // (0.6666...) both flow through the same shim. See `QuorumPolicySuite` for the formula
    // equivalence guarantee when fraction == 2/3.
    val q = math.max(1, QuorumPolicy.fromFraction(n, quorumThresholdFraction))

    // Count DISTINCT signers, not raw votes. `ViewChangeVote.highestKnownQc`/`TimeoutVote` are part of
    // the vote identity, so one signer can contribute multiple distinct Signed votes that all survive in
    // the cert's NonEmptySet. The builder and local assembler dedup by signer; this follower-side gate
    // must too, or ceil(q/2) equivocators could each emit two differing-QC votes to forge quorum.
    def certQuorumMet(voterIds: List[PeerId]): Boolean = {
      val signers = voterIds.toSet
      signers.size >= q || quorumShrink.exists(d => d.active && signers.count(d.anchor.contains) >= d.requiredQuorum)
    }

    val isSoloCore = n <= 1
    val isRoundStartView = proposalView === initialViewNumber.toLong

    if (proposalView === 0L) {
      if (proposalVcc.nonEmpty || proposalTimeoutCertificate.nonEmpty) Left(ProposalRejection("view0_proposal_must_not_carry_view_cert"))
      else Right(())
    } else {
      (proposalVcc, proposalTimeoutCertificate) match {
        case (None, None) if isSoloCore       => Right(())
        case (None, None) if isRoundStartView => Right(())
        case (None, None) =>
          Left(ProposalRejection(s"view${proposalView}_proposal_missing_view_cert", ProposalRejection.Kind.MissingViewCert))
        case (Some(_), Some(_)) => Left(ProposalRejection(s"view${proposalView}_proposal_multiple_view_certs"))
        // alpha.90 issue 2: enforce vcc.toView == proposal.view (and consequently
        // vcc.fromView == proposal.view - 1) before any other Some(vcc) checks. The VCC
        // assembly path in `StateTransitions.checkViewChangeAssembly` is strict --
        // `toView = fromView + 1L` from the current `state.viewNumber` -- so a VCC
        // accompanying a proposal at view V must have toView=V and fromView=V-1. Without
        // this check the alpha.90 seed-view bypass would be exploitable: a retry that
        // bumps `initialViewNumber` to e.g. 2 may still have a stale assembled VCC for
        // the 0->1 transition preserved in the slot (`clearResourcesPreservingDeclarations`
        // keeps `assembledVccR`), and an adversarial or buggy leader could embed that
        // stale cert on a fresh view=2 proposal without this gate.
        case (Some(vcc), None) if vcc.toView =!= proposalView || vcc.fromView =!= (proposalView - 1L) =>
          Left(
            ProposalRejection(
              s"vcc_view_mismatch vccFromView=${vcc.fromView} vccToView=${vcc.toView} proposalView=$proposalView",
              ProposalRejection.Kind.VccViewMismatch
            )
          )
        case (Some(vcc), None) if !certQuorumMet(vcc.votes.toNonEmptyList.toList.map(_.proofs.head.id.toPeerId)) =>
          Left(ProposalRejection(s"vcc_under_quorum votes=${vcc.votes.size} required=$q"))
        case (Some(vcc), None) if vcc.facilitatorsHash =!= facilitatorsHash =>
          Left(
            ProposalRejection(
              s"vcc_facilitators_mismatch vccFacHash=${vcc.facilitatorsHash.show.take(8)} ours=${facilitatorsHash.show.take(8)}"
            )
          )
        case (Some(vcc), None) =>
          val vccLastSnapshotHashes = vcc.votes.toNonEmptyList.toList.map(_.value.lastSnapshotHash).toSet
          if (vccLastSnapshotHashes.sizeCompare(1) > 0)
            Left(
              ProposalRejection(
                s"vcc_last_snapshot_mismatch hashes=${vccLastSnapshotHashes.size} expected=${lastSnapshotHash.show.take(8)}"
              )
            )
          else if (!vccLastSnapshotHashes.contains(lastSnapshotHash))
            Left(
              ProposalRejection(
                s"vcc_last_snapshot_mismatch vccLastSnap=${vccLastSnapshotHashes.head.show.take(8)} ours=${lastSnapshotHash.show.take(8)}"
              )
            )
          else {
            // Symmetric with B1/B2 -- every VCC voter must be in the deterministic wider witness pool, which is
            // WitnessPool.all UNIONED with roundStartFacilitators, matching the assembler's widerWitnessPoolAll in
            // StateTransitions. Without this re-check on the follower side, an adversarial leader could embed a VCC
            // built from out-of-pool voters and the rest of the cluster would accept it. The roundStartFacilitators
            // union is REQUIRED for the v33 shrink path: a shrunken cert is built from the anchor (completedSigners
            // INTERSECT roundStartFacilitators), whose voters are round-start facilitators but not necessarily in
            // WitnessPool.all -- without the union the assembler accepts and the follower rejects (vcc_voter_not_in_pool).
            val witnessPool = WitnessPool.all(eligibleFacilitators, peerQuality, minParticipationObservations).union(roundStartFacilitators)
            val nonWitnessPoolVoter = vcc.votes.toNonEmptyList.toList.find(sv => !witnessPool.contains(sv.proofs.head.id.toPeerId))
            nonWitnessPoolVoter match {
              case Some(bad) =>
                Left(ProposalRejection(s"vcc_voter_not_in_pool voter=${bad.proofs.head.id.show.take(8)}"))
              case None =>
                vcc.highestQcInVcc match {
                  case Some(qc) if qc.proposalHash =!= proposalHash =>
                    Left(
                      ProposalRejection(
                        s"highest_qc_carry_forward_violation qcHash=${qc.proposalHash.show.take(8)} proposalHash=${proposalHash.show.take(8)}"
                      )
                    )
                  case _ => Right(())
                }
            }
          }
        case (None, Some(tc)) if tc.toView =!= proposalView || tc.fromView =!= (proposalView - 1L) =>
          Left(
            ProposalRejection(
              s"tc_view_mismatch tcFromView=${tc.fromView} tcToView=${tc.toView} proposalView=$proposalView",
              ProposalRejection.Kind.TcViewMismatch
            )
          )
        case (None, Some(tc)) if !certQuorumMet(tc.votes.toNonEmptyList.toList.map(_.proofs.head.id.toPeerId)) =>
          Left(ProposalRejection(s"tc_under_quorum votes=${tc.votes.size} required=$q"))
        case (None, Some(tc)) if tc.facilitatorsHash =!= facilitatorsHash =>
          Left(
            ProposalRejection(
              s"tc_facilitators_mismatch tcFacHash=${tc.facilitatorsHash.show.take(8)} ours=${facilitatorsHash.show.take(8)}"
            )
          )
        case (None, Some(tc)) if tc.lastSnapshotHash =!= lastSnapshotHash =>
          Left(
            ProposalRejection(
              s"tc_last_snapshot_mismatch tcLastSnap=${tc.lastSnapshotHash.show.take(8)} ours=${lastSnapshotHash.show.take(8)}"
            )
          )
        case (None, Some(tc)) =>
          val tcVoteLastSnapshotHashes = tc.votes.toNonEmptyList.toList.map(_.value.lastSnapshotHash).toSet
          if (tcVoteLastSnapshotHashes.sizeCompare(1) > 0)
            Left(
              ProposalRejection(
                s"tc_vote_last_snapshot_mismatch hashes=${tcVoteLastSnapshotHashes.size} expected=${lastSnapshotHash.show.take(8)}"
              )
            )
          else if (!tcVoteLastSnapshotHashes.contains(lastSnapshotHash))
            Left(
              ProposalRejection(
                s"tc_vote_last_snapshot_mismatch tcVoteLastSnap=${tcVoteLastSnapshotHashes.head.show.take(8)} ours=${lastSnapshotHash.show.take(8)}"
              )
            )
          else {
            // Same wider witness pool as the VCC branch above (WitnessPool.all unioned with roundStartFacilitators,
            // matching the assembler's widerWitnessPoolAll); REQUIRED for the v33 shrink path's anchor voters.
            val witnessPool = WitnessPool.all(eligibleFacilitators, peerQuality, minParticipationObservations).union(roundStartFacilitators)
            val nonWitnessPoolVoter = tc.votes.toNonEmptyList.toList.find(sv => !witnessPool.contains(sv.proofs.head.id.toPeerId))
            nonWitnessPoolVoter match {
              case Some(bad) =>
                Left(ProposalRejection(s"tc_voter_not_in_pool voter=${bad.proofs.head.id.show.take(8)}"))
              case None =>
                val qcs = tc.votes.toNonEmptyList.toList.flatMap(_.value.highestKnownQc)
                val maxQcByView = qcs.groupBy(_.view).toList.sortBy(_._1).lastOption
                maxQcByView match {
                  case Some((view, atView)) if atView.map(_.proposalHash).toSet.sizeCompare(1) > 0 =>
                    Left(ProposalRejection(s"tc_divergent_highest_qc view=$view hashes=${atView.map(_.proposalHash).toSet.size}"))
                  case Some((_, atView)) if atView.head.proposalHash =!= proposalHash =>
                    Left(
                      ProposalRejection(
                        s"tc_highest_qc_carry_forward_violation qcHash=${atView.head.proposalHash.show
                            .take(8)} proposalHash=${proposalHash.show.take(8)}"
                      )
                    )
                  case _ => Right(())
                }
            }
          }
      }
    }
  }
}
