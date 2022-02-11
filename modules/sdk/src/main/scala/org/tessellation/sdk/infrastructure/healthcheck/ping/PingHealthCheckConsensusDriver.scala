package org.tessellation.sdk.infrastructure.healthcheck.ping

import scala.util.Try

import org.tessellation.schema.peer
import org.tessellation.sdk.domain.healthcheck.consensus.HealthCheckConsensusDriver
import org.tessellation.sdk.domain.healthcheck.consensus.types._

class PingHealthCheckConsensusDriver()
    extends HealthCheckConsensusDriver[PingHealthCheckKey, PingHealthCheckStatus, PingConsensusHealthStatus] {

  def removePeersWithParallelRound: Boolean = true

  def calculateConsensusOutcome(
    key: PingHealthCheckKey,
    ownStatus: PingHealthCheckStatus,
    selfId: peer.PeerId,
    receivedStatuses: List[PingConsensusHealthStatus]
  ): HealthCheckConsensusDecision = {
    def received = receivedStatuses.map(_.status) ++ List(ownStatus)
    def available = received.filter(isAvailable)
    def availablePercentage = Try(BigDecimal(available.size) / BigDecimal(received.size)).toOption.getOrElse {
      if (isAvailable(ownStatus)) BigDecimal(1) else BigDecimal(0)
    }

    if (availablePercentage >= BigDecimal(0.5d))
      PositiveOutcome(key.id)
    else
      NegativeOutcome(key.id)
  }

  def consensusHealthStatus(
    key: PingHealthCheckKey,
    ownStatus: PingHealthCheckStatus,
    roundId: HealthCheckRoundId,
    selfId: peer.PeerId
  ): PingConsensusHealthStatus = PingConsensusHealthStatus(key, roundId, selfId, ownStatus)

  private def isAvailable(status: PingHealthCheckStatus) = status.isInstanceOf[PeerAvailable]

}
