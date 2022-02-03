package org.tessellation.sdk.domain.healthcheck.types

import org.tessellation.sdk.domain.healthcheck.HealthCheckRound
import org.tessellation.sdk.domain.healthcheck.types.ConsensusRounds.InProgress

final case class ConsensusRounds[F[_], A <: HealthCheckStatus, B <: ConsensusHealthStatus[A]](
  historical: List[HistoricalRound],
  inProgress: InProgress[F, A, B]
)

object ConsensusRounds {
  private type Rounds[F[_], A <: HealthCheckStatus, B <: ConsensusHealthStatus[A]] = Map[HealthCheckKey, HealthCheckRound[F, A, B]]
  type InProgress[F[_], A <: HealthCheckStatus, B <: ConsensusHealthStatus[A]] = Rounds[F, A, B]
  type Finished[F[_], A <: HealthCheckStatus, B <: ConsensusHealthStatus[A]] = Rounds[F, A, B]
  type Outcome[F[_], A <: HealthCheckStatus, B <: ConsensusHealthStatus[A]] = Map[HealthCheckKey, (HealthCheckConsensusDecision, HealthCheckRound[F, A, B])]
}
