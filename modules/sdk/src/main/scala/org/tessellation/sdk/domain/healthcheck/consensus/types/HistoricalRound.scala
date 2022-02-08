package org.tessellation.sdk.domain.healthcheck.consensus.types

final case class HistoricalRound[K <: HealthCheckKey](key: K, roundId: HealthCheckRoundId)
