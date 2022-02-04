package org.tessellation.sdk.domain.healthcheck

import cats.Monad
import cats.effect.Ref
import cats.syntax.functor._

import org.tessellation.schema.peer.{Peer, PeerId}
import org.tessellation.sdk.domain.healthcheck.types._

class HealthCheckRound[F[_], A <: HealthCheckStatus, B <: ConsensusHealthStatus[A]]() {
  def getPeers: F[Set[Peer]] = ???
  def manageAbsent(peers: Set[PeerId]) = ???
  def isFinished: F[Boolean] = ???
  def manage: F[Unit] = ???
  def generateHistoricalData(decision: HealthCheckConsensusDecision): F[HistoricalRound] = ???
  def calculateOutcome: F[HealthCheckConsensusDecision] = ???
  def processProposal(proposal: B): F[Unit] = ???
  def start: F[Unit] = ???
  def getRoundIds: F[Set[HealthCheckRoundId]] = ???
  def addParallelRounds(key: HealthCheckKey)(roundIds: Set[HealthCheckRoundId]): F[Unit] = ???
}

object HealthCheckRound {

  def make[F[_]: Ref.Make: Monad, A <: HealthCheckStatus, B <: ConsensusHealthStatus[A]](
    // ...
  ): F[HealthCheckRound[F, A, B]] =
    Ref.of[F, Set[PeerId]](Set.empty).map { peers =>
      new HealthCheckRound[F, A, B]()
    }
}
