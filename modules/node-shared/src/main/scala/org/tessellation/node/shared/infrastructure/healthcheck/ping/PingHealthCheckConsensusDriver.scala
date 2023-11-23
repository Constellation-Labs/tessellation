package org.tessellation.node.shared.infrastructure.healthcheck.ping

import scala.util.Try

import org.tessellation.node.shared.domain.healthcheck.consensus.HealthCheckConsensusDriver
import org.tessellation.node.shared.domain.healthcheck.consensus.types._
import org.tessellation.schema.peer.PeerId

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
    roundIds: Set[HealthCheckRoundId],
    selfId: PeerId,
    clusterState: Set[PeerId]
  ): PingConsensusHealthStatus = PingConsensusHealthStatus(key, roundIds, selfId, ownStatus, clusterState)

  private def isAvailable(status: PingHealthCheckStatus) = status.isInstanceOf[PeerAvailable]
  private def isMismatch(status: PingHealthCheckStatus) = status.isInstanceOf[PeerMismatch]

}
