package io.constellationnetwork.node.shared.infrastructure.consensus.state

import io.constellationnetwork.node.shared.infrastructure.consensus.FacilitatorSelector
import io.constellationnetwork.node.shared.infrastructure.consensus.declaration.AdmissionCertificate
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash

/** Deterministic cap for the assembled `AdmissionCertificate`s a leader attaches to an outgoing Proposal.
  *
  * Proposal validation (`validateProposalAcs` in both advancers) rejects any proposal carrying more than `math.max(0,
  * config.activeAdmissionMaxExpansionPerRound)` admission certificates (`acs_too_many`). Proposal CREATION must therefore never attach more
  * than that cap. Post-stall, several candidates can assemble certificates for the same round; without this cap every leader proposal
  * carries the full set and every validator rejects it -- a permanent proposal-rejection loop (live wedge at ordinal 3150066, alpha.149).
  *
  * Shared between dag-l0's `GlobalSnapshotConsensusStateAdvancer` and currency-l0's `CurrencySnapshotConsensusStateAdvancer` (see
  * `feedback_share_logic_no_drift` -- consensus-adjacent logic must not be replicated).
  *
  * Selection is deterministic under input-ordering permutations: candidates are sorted by `AdmissionCertificate.ordering` (lexicographic on
  * the target `PeerId` value first, then reason / facilitatorsHash / lastSnapshotHash as tie-breakers) and the first `cap` are kept. Two
  * leaders building from the same assembled set always pick the same certificates, so proposal hashes agree.
  */
object AdmissionCertificateSelector {

  /** @param kept
    *   the certificates to attach, sorted by `AdmissionCertificate.ordering`, `size <= math.max(0, activeAdmissionMaxExpansionPerRound)`
    * @param dropped
    *   the certificates excluded by the cap (also sorted); non-empty triggers the `stage=proposal_cap` log + the
    *   `dag_consensus_admission_cert_capped_total` counter at call sites
    */
  final case class Selection(kept: List[AdmissionCertificate], dropped: List[AdmissionCertificate])

  /** Caps `assembled` at the validation limit. The cap is computed EXACTLY as `validateProposalAcs` computes its
    * `maxAdmissionCertificates`: `math.max(0, activeAdmissionMaxExpansionPerRound)`.
    */
  def select(assembled: Iterable[AdmissionCertificate], activeAdmissionMaxExpansionPerRound: Int): Selection = {
    val cap = math.max(0, activeAdmissionMaxExpansionPerRound)
    val (kept, dropped) = assembled.toList.sorted(AdmissionCertificate.ordering).splitAt(cap)
    Selection(kept, dropped)
  }

  /** Proposal-construction policy: prioritize the existing penalty/probation recovery lane, then cap certificates within each priority by
    * the same parent-entropy rendezvous ranking used for open nominations, with the certificate's existing ordering as the final tie-break.
    * Recovery priority prevents an open Ready-at-tip certificate from consuming the only proposal slot while an already-evicted peer has a
    * quorum certificate waiting. Rendezvous ordering still prevents a permanent lowest-PeerId preference among peers in the same lane.
    *
    * The apply-site defense remains on [[select]], intentionally. Validation rejects over-cap proposals, so apply selection is unreachable
    * for valid traffic; preserving its legacy ordering avoids turning that version-stability safety net into construction policy.
    */
  def selectForProposal(
    assembled: Iterable[AdmissionCertificate],
    activeAdmissionMaxExpansionPerRound: Int,
    entropy: Hash,
    probation: Set[PeerId] = Set.empty
  ): Selection = {
    val cap = math.max(0, activeAdmissionMaxExpansionPerRound)
    val targetOrdering = FacilitatorSelector.orderByScore(entropy).toOrdering
    val ranked = assembled.toList.sortWith { (left, right) =>
      val leftIsProbation = probation.contains(left.targetPeer)
      val rightIsProbation = probation.contains(right.targetPeer)

      if (leftIsProbation != rightIsProbation) leftIsProbation
      else {
        val targetComparison = targetOrdering.compare(left.targetPeer, right.targetPeer)
        if (targetComparison != 0) targetComparison < 0
        else AdmissionCertificate.ordering.compare(left, right) < 0
      }
    }
    val (kept, dropped) = ranked.splitAt(cap)
    Selection(kept, dropped)
  }
}
