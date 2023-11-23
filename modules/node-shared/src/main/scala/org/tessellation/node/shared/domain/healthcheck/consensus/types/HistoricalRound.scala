package org.tessellation.node.shared.domain.healthcheck.consensus.types

final case class HistoricalRound[K <: HealthCheckKey, A <: HealthCheckStatus, B <: ConsensusHealthStatus[K, A]](
  key: K,
  roundIds: Set[HealthCheckRoundId],
  ownProposal: B,
  decision: HealthCheckConsensusDecision
)
