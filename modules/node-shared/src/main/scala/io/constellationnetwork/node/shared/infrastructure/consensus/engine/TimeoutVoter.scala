package io.constellationnetwork.node.shared.infrastructure.consensus.engine

import cats.Applicative

import io.constellationnetwork.node.shared.infrastructure.consensus.declaration.{ProposalQC, TimeoutReason}

trait TimeoutVoter[F[_], Key] {
  def emitTimeoutVote(
    key: Key,
    fromView: Long,
    toView: Long,
    highestKnownQc: Option[ProposalQC],
    reason: TimeoutReason
  ): F[Unit]
}

object TimeoutVoter {
  def noop[F[_]: Applicative, Key]: TimeoutVoter[F, Key] = new TimeoutVoter[F, Key] {
    def emitTimeoutVote(
      key: Key,
      fromView: Long,
      toView: Long,
      highestKnownQc: Option[ProposalQC],
      reason: TimeoutReason
    ): F[Unit] = Applicative[F].unit
  }
}
