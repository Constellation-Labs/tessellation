package org.tessellation.sdk.domain.healthcheck.consensus.types

import org.tessellation.sdk.domain.healthcheck.consensus.HealthCheckConsensusRound
import org.tessellation.sdk.domain.healthcheck.consensus.types.ConsensusRounds.InProgress

final case class ConsensusRounds[F[_], K <: HealthCheckKey, A <: HealthCheckStatus, B <: ConsensusHealthStatus[K, A]](
  historical: List[HistoricalRound[K]],
  inProgress: InProgress[F, K, A, B]
)

object ConsensusRounds {
  private type Rounds[F[_], K <: HealthCheckKey, A <: HealthCheckStatus, B <: ConsensusHealthStatus[K, A]] =
    Map[K, HealthCheckConsensusRound[F, K, A, B]]

  type InProgress[F[_], K <: HealthCheckKey, A <: HealthCheckStatus, B <: ConsensusHealthStatus[K, A]] =
    Rounds[F, K, A, B]

  type Finished[F[_], K <: HealthCheckKey, A <: HealthCheckStatus, B <: ConsensusHealthStatus[K, A]] =
    Rounds[F, K, A, B]

  type Outcome[F[_], K <: HealthCheckKey, A <: HealthCheckStatus, B <: ConsensusHealthStatus[K, A]] =
    Map[K, (HealthCheckConsensusDecision, HealthCheckConsensusRound[F, K, A, B])]
}
