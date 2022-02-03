package org.tessellation.sdk.domain.healthcheck

import org.tessellation.schema.peer.{Peer, PeerId}
import org.tessellation.sdk.domain.healthcheck.types.{HealthCheckConsensusDecision, HistoricalRound}

class HealthCheckRound[F[_]] {
  def getPeers: F[Set[Peer]] = ???
  def manageAbsent(peers: Set[PeerId]) = ???
  def isFinished: F[Boolean] = ???
  def manage: F[Unit] = ???
  def generateHistoricalData(decision: HealthCheckConsensusDecision): F[HistoricalRound] = ???
  def calculateOutcome: F[HealthCheckConsensusDecision] = ???
}
