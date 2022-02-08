package org.tessellation.sdk.domain.healthcheck.consensus.types

import org.tessellation.sdk.domain.healthcheck.consensus.HealthCheckConsensusRound
import org.tessellation.sdk.domain.healthcheck.consensus.types.ConsensusRounds.InProgress

final case class ConsensusRounds[F[_], A <: HealthCheckStatus, B <: ConsensusHealthStatus[A]](
  historical: List[HistoricalRound],
  inProgress: InProgress[F, A, B]
)

object ConsensusRounds {
  private type Rounds[F[_], A <: HealthCheckStatus, B <: ConsensusHealthStatus[A]] =
    Map[HealthCheckKey, HealthCheckConsensusRound[F, A, B]]
  type InProgress[F[_], A <: HealthCheckStatus, B <: ConsensusHealthStatus[A]] = Rounds[F, A, B]
  type Finished[F[_], A <: HealthCheckStatus, B <: ConsensusHealthStatus[A]] = Rounds[F, A, B]

  type Outcome[F[_], A <: HealthCheckStatus, B <: ConsensusHealthStatus[A]] =
    Map[HealthCheckKey, (HealthCheckConsensusDecision, HealthCheckConsensusRound[F, A, B])]
}
