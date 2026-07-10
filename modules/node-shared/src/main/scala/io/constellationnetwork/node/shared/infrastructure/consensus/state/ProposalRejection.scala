package io.constellationnetwork.node.shared.infrastructure.consensus.state

/** Reason a Proposal was rejected during structural / signature validation in the advancer's `resolveLeaderProposal` pipeline.
  *
  * The `code` is a stable structured-log string preserving the pre-typed format (e.g., `"ecs_under_quorum target=… votes=… required=…"`).
  * Existing operator dashboards and grep queries pivot on the leading prefix (`ecs_`, `acs_`, `vcc_`, `obs_resp_`, `view0_`, `view{N}_`,
  * `highest_qc_`) so those keep working -- `code` is for HUMANS and dashboards.
  *
  * `kind` is the typed, recovery-relevant classification that the advancers' stale-proposal self-heal control flow keys on (stale-slot
  * prune / stale-local-view soft-reset). It is NEVER re-derived from the `code` string: a producer reword of a `code` (which dashboards
  * grep on) can therefore no longer silently disable self-heal -- the prior `code.startsWith(...) && code.endsWith(...)` control flow had
  * exactly that footgun, duplicated across the dag-l0 and currency-l0 advancers. Diagnostic-only rejections leave `kind = Other`.
  */
final case class ProposalRejection(code: String, kind: ProposalRejection.Kind = ProposalRejection.Kind.Other) {

  /** The leader proposed at view > 0 without carrying any view certificate (`view{N}_proposal_missing_view_cert`). */
  def isMissingViewCert: Boolean = kind match {
    case ProposalRejection.Kind.MissingViewCert => true
    case _                                      => false
  }

  /** Any recovery-relevant view/cert mismatch (missing view cert, VCC view mismatch, or TC view mismatch) that should drive the advancer's
    * stale-local-view recovery. Equivalent to "kind is not Other".
    */
  def triggersStaleViewRecovery: Boolean = kind match {
    case ProposalRejection.Kind.MissingViewCert => true
    case ProposalRejection.Kind.VccViewMismatch => true
    case ProposalRejection.Kind.TcViewMismatch  => true
    case ProposalRejection.Kind.Other           => false
  }
}

object ProposalRejection {

  /** Recovery-relevant classification of a proposal rejection. The advancers branch on this typed value, not on the `code` string. */
  sealed trait Kind
  object Kind {

    /** `view{N}_proposal_missing_view_cert`: proposal at view > 0 carried neither a VCC nor a TC. */
    case object MissingViewCert extends Kind

    /** `vcc_view_mismatch`: the carried ViewChangeCertificate's `toView` does not equal `proposalView`. */
    case object VccViewMismatch extends Kind

    /** `tc_view_mismatch`: the carried TimeoutCertificate's `toView` does not equal `proposalView`. */
    case object TcViewMismatch extends Kind

    /** Any other (diagnostic-only) rejection -- not recovery-relevant; the advancer only logs it. */
    case object Other extends Kind
  }
}
