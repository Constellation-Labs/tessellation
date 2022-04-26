package org.tessellation.sdk.domain.healthcheck.consensus.types

import org.tessellation.sdk.domain.healthcheck.consensus.HealthCheckConsensusRound
import org.tessellation.sdk.domain.healthcheck.consensus.types.ConsensusRounds.InProgress

final case class ConsensusRounds[F[_], K <: HealthCheckKey, A <: HealthCheckStatus, B <: ConsensusHealthStatus[K, A], C <: HealthCheckConsensusDecision](
  historical: List[HistoricalRound[K]],
  inProgress: InProgress[F, K, A, B, C]
)

object ConsensusRounds {
  private type Rounds[F[_], K <: HealthCheckKey, A <: HealthCheckStatus, B <: ConsensusHealthStatus[K, A], C <: HealthCheckConsensusDecision] =
    Map[K, HealthCheckConsensusRound[F, K, A, B, C]]

  type InProgress[F[_], K <: HealthCheckKey, A <: HealthCheckStatus, B <: ConsensusHealthStatus[K, A], C <: HealthCheckConsensusDecision] =
    Rounds[F, K, A, B, C]

  type Finished[F[_], K <: HealthCheckKey, A <: HealthCheckStatus, B <: ConsensusHealthStatus[K, A], C <: HealthCheckConsensusDecision] =
    Rounds[F, K, A, B, C]

  type Outcome[F[_], K <: HealthCheckKey, A <: HealthCheckStatus, B <: ConsensusHealthStatus[K, A], C <: HealthCheckConsensusDecision] =
    Map[K, (C, HealthCheckConsensusRound[F, K, A, B, C])]
}
