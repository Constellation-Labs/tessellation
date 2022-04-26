package org.tessellation.sdk.infrastructure.healthcheck.ping

import scala.util.Try

import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.domain.healthcheck.consensus.HealthCheckConsensusDriver
import org.tessellation.sdk.domain.healthcheck.consensus.types._

class PingHealthCheckConsensusDriver()
    extends HealthCheckConsensusDriver[
      PingHealthCheckKey,
      PingHealthCheckStatus,
      PingConsensusHealthStatus,
      PingHealthCheckConsensusDecision
    ] {

  def removePeersWithParallelRound: Boolean = true

  def calculateConsensusOutcome(
    key: PingHealthCheckKey,
    ownStatus: PingHealthCheckStatus,
    selfId: PeerId,
    receivedStatuses: List[PingConsensusHealthStatus]
  ): PingHealthCheckConsensusDecision = {
    def received = receivedStatuses.map(_.status) ++ List(ownStatus)
    def available = received.filter(isAvailable)
    def mismatch = received.filter(isMismatch)

    def percentage(a: Int, total: Int, fn: PingHealthCheckStatus => Boolean): BigDecimal =
      Try(BigDecimal(a) / BigDecimal(total)).toOption.getOrElse {
        if (fn(ownStatus)) BigDecimal(1) else BigDecimal(0)
      }

    def availablePercentage = percentage(available.size, received.size, isAvailable)
    def mismatchPercentage = percentage(mismatch.size, received.size, isMismatch)

    if (availablePercentage >= BigDecimal(0.5d))
      DecisionKeepPeer
    else if (mismatchPercentage >= BigDecimal(0.5d))
      DecisionPeerMismatch
    else
      DecisionDropPeer
  }

  def consensusHealthStatus(
    key: PingHealthCheckKey,
    ownStatus: PingHealthCheckStatus,
    roundId: HealthCheckRoundId,
    selfId: PeerId
  ): PingConsensusHealthStatus = PingConsensusHealthStatus(key, roundId, selfId, ownStatus)

  private def isAvailable(status: PingHealthCheckStatus) = status.isInstanceOf[PeerAvailable]
  private def isMismatch(status: PingHealthCheckStatus) = status.isInstanceOf[PeerMismatch]

}
