package org.tessellation.sdk.domain.healthcheck.services

import cats.effect.Ref
import cats.syntax.bifunctor._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.traverse._
import cats.{Applicative, Monad}

import org.tessellation.sdk.domain.healthcheck.types.ConsensusRounds

abstract class HealthCheckConsensus[F[_]: Monad]() extends HealthCheck[F] {
  def allRounds: Ref[F, ConsensusRounds[F]]
  def roundsInProgress = allRounds.get.map(_.inProgress)

  final override def trigger(): F[Unit] =
    triggerRound()

  def triggerRound(): F[Unit] =
    roundsInProgress.flatMap { inProgress =>
      if (inProgress.nonEmpty) manageRounds(inProgress) else Applicative[F].unit
    }

  def manageRounds(rounds: ConsensusRounds.InProgress[F]): F[Unit] =
    partition(rounds).flatMap {
      case (finished, inProgress) => Applicative[F].unit
    }

  def partition(
    rounds: ConsensusRounds.InProgress[F]
  ): F[(ConsensusRounds.Finished[F], ConsensusRounds.InProgress[F])] = {
    def ignoreTupleRight[A, B, C](m: Map[A, (B, C)]): Map[A, B] = m.view.mapValues(_._1).toMap

    rounds.toList.traverse {
      case (key, consensus) => consensus.isFinished.map(finished => (key, (consensus, finished)))
    }.map(_.toMap.partition { case (_, (_, finished)) => finished })
      .map(_.bimap(ignoreTupleRight, ignoreTupleRight))
  }

}
