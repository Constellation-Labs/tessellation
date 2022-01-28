package org.tessellation.sdk.domain.healthcheck.types

import org.tessellation.sdk.domain.healthcheck.HealthCheckRound
import org.tessellation.sdk.domain.healthcheck.types.ConsensusRounds.InProgress

final case class ConsensusRounds[F[_]](
  historical: List[HistoricalRound],
  inProgress: InProgress[F]
)

object ConsensusRounds {
  private type Rounds[F[_]] = Map[HealthCheckKey, HealthCheckRound[F]]
  type InProgress[F[_]] = Rounds[F]
  type Finished[F[_]] = Rounds[F]
}
