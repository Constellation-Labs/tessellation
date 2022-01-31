package org.tessellation.sdk.domain.healthcheck

import org.tessellation.sdk.domain.healthcheck.types.{HealthCheckConsensusDecision, HistoricalRound}

class HealthCheckRound[F[_]] {
  def isFinished: F[Boolean] = ???
  def manage: F[Unit] = ???
  def generateHistoricalData(decision: HealthCheckConsensusDecision): F[HistoricalRound] = ???
}
