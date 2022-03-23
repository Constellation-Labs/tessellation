package org.tessellation.sdk.infrastructure.healthcheck.declaration

import cats.syntax.order._

import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.domain.healthcheck.consensus.HealthCheckConsensusDriver
import org.tessellation.sdk.domain.healthcheck.consensus.types._

class PeerDeclarationHealthCheckDriver[K] extends HealthCheckConsensusDriver[Key[K], Health, Status[K]] {

  def removePeersWithParallelRound: Boolean = true

  def calculateConsensusOutcome(
    key: Key[K],
    ownStatus: Health,
    selfId: PeerId,
    receivedStatuses: List[Status[K]]
  ): HealthCheckConsensusDecision = {
    val statuses = ownStatus :: receivedStatuses.map(_.status)

    val threshold = statuses.size / 2
    val negativeCount = statuses.count(_ === TimedOut)

    if (negativeCount > threshold)
      NegativeOutcome(key.id)
    else
      PositiveOutcome(key.id)
  }

  def consensusHealthStatus(key: Key[K], ownStatus: Health, roundId: HealthCheckRoundId, selfId: PeerId): Status[K] =
    PeerDeclarationConsensusHealthStatus(key, roundId, selfId, ownStatus)
}
