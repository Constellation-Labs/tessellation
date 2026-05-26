package io.constellationnetwork.node.shared.infrastructure.consensus.state

import cats.syntax.eq._
import cats.syntax.foldable._
import cats.syntax.show._

import io.constellationnetwork.node.shared.infrastructure.consensus.declaration.ViewChangeCertificate
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
  *   - `proposalView == 0` && Some(vcc): reject `view0_proposal_must_not_carry_vcc`
  *   - `proposalView == 0` && None: accept
  *   - `proposalView > 0` && None && `coreSize <= 1`: accept (alpha.89 solo-core bypass)
  *   - `proposalView > 0` && None && `proposalView == initialViewNumber`: accept (round-start certified seed bypass)
  *   - `proposalView > 0` && None: reject `view{N}_proposal_missing_vcc`
  *   - `proposalView > 0` && Some(vcc) && `vcc.toView != proposalView`: reject `vcc_view_mismatch` (alpha.90 issue 2 -- closes the latent
  *     gap that the alpha.90 seed-view bypass would otherwise expose: a stale 0->1 VCC could be embedded on a view=2 proposal without this
  *     check)
  *   - `vcc.votes.size < quorum`: reject `vcc_under_quorum`
  *   - `vcc.facilitatorsHash =!= facilitatorsHash`: reject `vcc_facilitators_mismatch`
  *   - any VCC voter outside the wider witness pool: reject `vcc_voter_not_in_pool`
  *   - `vcc.highestQcInVcc` exists and disagrees with `proposalHash`: reject `highest_qc_carry_forward_violation`
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
    * @param eligibleFacilitators
    *   `ConsensusState.eligibleFacilitators.value.toSet` -- the eligibility set used to compute the wider VCC witness pool.
    * @param peerQuality
    *   `ConsensusState.lastOutcome.peerQuality.toMap` -- historical (completed, participated) counters that widen the pool to long-running
    *   participants.
    * @param quorumThresholdFraction
    *   `ConsensusConfig.quorumThresholdFraction`.
    * @param minParticipationObservations
    *   `ConsensusConfig.minParticipationObservations`.
    */
  def validate(
    proposalView: Long,
    proposalHash: Hash,
    proposalVcc: Option[ViewChangeCertificate],
    initialViewNumber: Int,
    coreSize: Int,
    facilitatorsHash: Hash,
    eligibleFacilitators: Set[PeerId],
    peerQuality: Map[PeerId, (Int, Int)],
    quorumThresholdFraction: Double,
    minParticipationObservations: Int
  ): Either[ProposalRejection, Unit] = {
    val n = coreSize
    // Integer supermajority via shared `QuorumPolicy.fromFraction`. The legacy `ceil(n * fraction)`
    // math is preserved here (rather than switching to `supermajority(n)`) because the validator
    // API still accepts a configurable fraction so dev unanimity (1.0) and testnet supermajority
    // (0.6666...) both flow through the same shim. See `QuorumPolicySuite` for the formula
    // equivalence guarantee when fraction == 2/3.
    val q = math.max(1, QuorumPolicy.fromFraction(n, quorumThresholdFraction))
    val isSoloCore = n <= 1
    val isRoundStartView = proposalView === initialViewNumber.toLong

    if (proposalView === 0L) {
      if (proposalVcc.nonEmpty) Left(ProposalRejection("view0_proposal_must_not_carry_vcc"))
      else Right(())
    } else {
      proposalVcc match {
        case None if isSoloCore       => Right(())
        case None if isRoundStartView => Right(())
        case None                     => Left(ProposalRejection(s"view${proposalView}_proposal_missing_vcc"))
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
        case Some(vcc) if vcc.toView =!= proposalView || vcc.fromView =!= (proposalView - 1L) =>
          Left(
            ProposalRejection(
              s"vcc_view_mismatch vccFromView=${vcc.fromView} vccToView=${vcc.toView} proposalView=$proposalView"
            )
          )
        case Some(vcc) if vcc.votes.size < q =>
          Left(ProposalRejection(s"vcc_under_quorum votes=${vcc.votes.size} required=$q"))
        case Some(vcc) if vcc.facilitatorsHash =!= facilitatorsHash =>
          Left(
            ProposalRejection(
              s"vcc_facilitators_mismatch vccFacHash=${vcc.facilitatorsHash.show.take(8)} ours=${facilitatorsHash.show.take(8)}"
            )
          )
        case Some(vcc) =>
          // Symmetric with B1/B2 -- every VCC voter must be in the deterministic wider witness pool. The assembler
          // (StateTransitions.checkViewChangeAssembly) filters by the same pool; without this re-check on the follower
          // side, an adversarial leader could embed a VCC built from out-of-pool voters and the rest of the cluster
          // would accept it.
          val witnessPool = WitnessPool.all(eligibleFacilitators, peerQuality, minParticipationObservations)
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
    }
  }
}
