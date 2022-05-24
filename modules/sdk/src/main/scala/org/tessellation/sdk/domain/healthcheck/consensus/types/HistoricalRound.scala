package org.tessellation.sdk.domain.healthcheck.consensus.types

final case class HistoricalRound[K <: HealthCheckKey](
  key: K,
  roundIds: Set[HealthCheckRoundId],
  decision: HealthCheckConsensusDecision
)
