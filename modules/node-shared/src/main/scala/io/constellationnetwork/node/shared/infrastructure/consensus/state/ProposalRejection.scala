package io.constellationnetwork.node.shared.infrastructure.consensus.state

/** Reason a Proposal was rejected during structural / signature validation in the advancer's `resolveLeaderProposal` pipeline.
  *
  * The `code` is a stable structured-log string preserving the pre-typed format (e.g., `"ecs_under_quorum target=… votes=… required=…"`).
  * Existing operator dashboards and grep queries pivot on the leading prefix (`ecs_`, `acs_`, `vcc_`, `obs_resp_`, `view0_`, `view{N}_`,
  * `highest_qc_`) so those keep working. The wrapper only narrows the error channel type — call sites that previously returned `Left("…")`
  * now return `Left(ProposalRejection("…"))`.
  */
final case class ProposalRejection(code: String) extends AnyVal
