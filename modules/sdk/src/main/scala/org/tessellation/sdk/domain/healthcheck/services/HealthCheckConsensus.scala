package org.tessellation.sdk.domain.healthcheck.services

import cats.Applicative
import cats.effect.{Ref, Spawn}
import cats.syntax.applicative._
import cats.syntax.bifunctor._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.traverse._

import org.tessellation.schema.node.NodeState
import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.domain.cluster.storage.ClusterStorage
import org.tessellation.sdk.domain.healthcheck.HealthCheckRound
import org.tessellation.sdk.domain.healthcheck.types._

abstract class HealthCheckConsensus[F[_]: Spawn](clusterStorage: ClusterStorage[F]) extends HealthCheck[F] {
  def allRounds: Ref[F, ConsensusRounds[F]]
  def roundsInProgress = allRounds.get.map(_.inProgress)
  def historicalRounds = allRounds.get.map(_.historical)

  def peersUnderConsensus: F[Set[PeerId]] =
    roundsInProgress.map(_.keySet.map(_.id))

  final override def trigger(): F[Unit] =
    triggerRound()

  private def triggerRound(): F[Unit] =
    roundsInProgress.flatMap { inProgress =>
      if (inProgress.nonEmpty) manageRounds(inProgress) else Applicative[F].unit
    }

  private def manageRounds(rounds: ConsensusRounds.InProgress[F]): F[Unit] = {
    def checkRounds(inProgress: ConsensusRounds.InProgress[F]): F[ConsensusRounds.Finished[F]] =
      clusterStorage.getPeers.flatTap { peers =>
        def absentPeers = NodeState.absent(peers).map(_.id)

        rounds.values.toList.traverse { round =>
          round.getPeers
            .map(_.map(_.id).intersect(absentPeers))
            .flatMap(round.manageAbsent)
            .void
        }
      }.flatMap { _ =>
        partition(inProgress).map { case (finished, _) => finished }
      }

    partition(rounds).flatMap {
      case (finished, inProgress) => checkRounds(inProgress).map(r => (finished ++ r, inProgress -- r.keySet))
    }.flatMap {
      case (ready, toManage) =>
        toManage.values.toList
          .map(_.manage)
          .map(Spawn[F].start)
          .sequence
          .as(ready)
    }.flatMap {
      calculateOutcome
    }.flatTap { outcome =>
      def positive = outcome.filter { case (_, (decision, _)) => decision.isInstanceOf[PositiveOutcome] }
      def negative = outcome -- positive.keySet

      def run = onPositiveOutcome(positive) >> onNegativeOutcome(negative)

      Spawn[F].start(run)
    }.flatMap {
      _.values.toList.traverse {
        case (decision, round) => round.generateHistoricalData(decision)
      }
    }.flatMap { finishedRounds =>
      allRounds.modify {
        case ConsensusRounds(historical, inProgress) =>
          def updatedHistorical = historical ++ finishedRounds
          def updatedInProgress = inProgress -- finishedRounds.map(_.key).toSet

          (ConsensusRounds(updatedHistorical, updatedInProgress), ())
      }
    }
  }

  def startOwnRound = ???
  def participateInRound = ???

  def handleProposal = ???
  def handleConsensusHealthProposal = ???
  def handleProposalForHistoricalRound = ???

  protected def onPositiveOutcome(
    peers: ConsensusRounds.Outcome[F]
  ): F[Unit] = Applicative[F].unit

  protected def onNegativeOutcome(
    peers: ConsensusRounds.Outcome[F]
  ): F[Unit] =
    peers.keys.toList.traverse { key =>
      clusterStorage.removePeer(key.id)
    }.void

  private def calculateOutcome(rounds: ConsensusRounds.Finished[F]): F[ConsensusRounds.Outcome[F]] =
    if (rounds.isEmpty)
      Map.empty[HealthCheckKey, (HealthCheckConsensusDecision, HealthCheckRound[F])].pure[F]
    else
      rounds.toList.traverse {
        case (key, round) =>
          round.calculateOutcome
            .map(outcome => (key, (outcome, round)))
      }.map(_.toMap)

  private def partition(
    rounds: ConsensusRounds.InProgress[F]
  ): F[(ConsensusRounds.Finished[F], ConsensusRounds.InProgress[F])] = {
    def ignoreTupleRight[A, B, C](m: Map[A, (B, C)]): Map[A, B] = m.view.mapValues(_._1).toMap

    rounds.toList.traverse {
      case (key, consensus) => consensus.isFinished.map(finished => (key, (consensus, finished)))
    }.map(_.toMap.partition { case (_, (_, finished)) => finished })
      .map(_.bimap(ignoreTupleRight, ignoreTupleRight))
  }

}
