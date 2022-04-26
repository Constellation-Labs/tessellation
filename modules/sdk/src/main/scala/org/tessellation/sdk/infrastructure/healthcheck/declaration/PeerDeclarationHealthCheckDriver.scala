package org.tessellation.sdk.infrastructure.healthcheck.declaration

import cats.syntax.order._

import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.domain.healthcheck.consensus.HealthCheckConsensusDriver
import org.tessellation.sdk.domain.healthcheck.consensus.types._

class PeerDeclarationHealthCheckDriver[K] extends HealthCheckConsensusDriver[Key[K], Health, Status[K], Decision] {

  def removePeersWithParallelRound: Boolean = true

  def calculateConsensusOutcome(
    key: Key[K],
    ownStatus: Health,
    selfId: PeerId,
    receivedStatuses: List[Status[K]]
  ): Decision = {
    val statuses = ownStatus :: receivedStatuses.map(_.status)

    val threshold = statuses.size / 2
    val negativeCount = statuses.count(_ === TimedOut)

    if (negativeCount > threshold)
      NegativeOutcome
    else
      PositiveOutcome
  }

  def consensusHealthStatus(key: Key[K], ownStatus: Health, roundId: HealthCheckRoundId, selfId: PeerId): Status[K] =
    PeerDeclarationConsensusHealthStatus(key, roundId, selfId, ownStatus)
}
