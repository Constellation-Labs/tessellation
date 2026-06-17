package io.constellationnetwork.node.shared.infrastructure.consensus.state

import io.constellationnetwork.node.shared.infrastructure.consensus.declaration.AdmissionCertificate

/** Deterministic cap for the assembled `AdmissionCertificate`s a leader attaches to an outgoing Proposal.
  *
  * Proposal validation (`validateProposalAcs` in both advancers) rejects any proposal carrying more than `math.max(0,
  * config.activeAdmissionMaxExpansionPerRound)` admission certificates (`acs_too_many`). Proposal CREATION must therefore never attach more
  * than that cap. Post-stall, several candidates can assemble certificates for the same round; without this cap every leader proposal
  * carries the full set and every validator rejects it -- a permanent proposal-rejection loop (live wedge at ordinal 3150066, alpha.149).
  *
  * Shared between dag-l0's `GlobalSnapshotConsensusStateAdvancer` and currency-l0's `CurrencySnapshotConsensusStateAdvancer` (see
  * `feedback_share_logic_no_drift` -- consensus-adjacent logic must not be replicated) so the leader-build sites, the re-spread sites and
  * the apply-site defense-in-depth cannot drift from each other or from validation.
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
}
