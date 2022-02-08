package org.tessellation.sdk.domain.healthcheck.consensus

import cats.Applicative
import cats.effect.{Clock, Ref, Spawn}
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.bifunctor._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.option._
import cats.syntax.traverse._

import org.tessellation.effects.GenUUID
import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.domain.cluster.storage.ClusterStorage
import org.tessellation.sdk.domain.gossip.Gossip
import org.tessellation.sdk.domain.healthcheck.consensus.HealthCheckConsensusRound
import org.tessellation.sdk.domain.healthcheck.consensus.types._
import org.tessellation.sdk.domain.healthcheck.consensus.types.types.RoundId
import org.tessellation.sdk.domain.healthcheck.services.HealthCheck

abstract class HealthCheckConsensus[
  F[_]: Clock: GenUUID: Spawn: Ref.Make,
  A <: HealthCheckStatus,
  B <: ConsensusHealthStatus[A]
](
  clusterStorage: ClusterStorage[F],
  selfId: PeerId,
  driver: HealthCheckConsensusDriver[A, B],
  gossip: Gossip[F]
) extends HealthCheck[F] {

  def allRounds: Ref[F, ConsensusRounds[F, A, B]]
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

  private def manageRounds(rounds: ConsensusRounds.InProgress[F, A, B]): F[Unit] = {
    def checkRounds(inProgress: ConsensusRounds.InProgress[F, A, B]): F[ConsensusRounds.Finished[F, A, B]] =
      clusterStorage.getPeers.flatTap { peers =>
        rounds.values.toList.traverse { round =>
          round.managePeers(peers)
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
      def negative = outcome.filter { case (_, (decision, _)) => decision.isInstanceOf[NegativeOutcome] }
      def nonNegative = outcome -- negative.keySet

      def run = onNegativeOutcome(negative) >> onNonNegativeOutcome(nonNegative)

      Spawn[F].start(run)
    }.flatMap {
      _.values.toList.traverse {
        case (decision, round) => round.generateHistoricalData(decision)
      }
    }.flatMap { finishedRounds =>
      allRounds.update {
        case ConsensusRounds(historical, inProgress) =>
          def updatedHistorical = historical ++ finishedRounds
          def updatedInProgress = inProgress -- finishedRounds.map(_.key).toSet

          ConsensusRounds(updatedHistorical, updatedInProgress)
      }
    }
  }

  private def createRoundId: F[RoundId] = GenUUID[F].make.map(RoundId.apply)

  def startOwnRound(key: HealthCheckKey) =
    createRoundId.map(HealthCheckRoundId(_, selfId)).flatMap {
      startRound(key, _)
    }

  def participateInRound(key: HealthCheckKey, roundId: HealthCheckRoundId): F[Unit] =
    startRound(key, roundId)

  def startRound(
    key: HealthCheckKey,
    roundId: HealthCheckRoundId
  ): F[Unit] =
    clusterStorage.getPeers
      .map(_.map(_.id))
      .flatMap { initialPeers =>
        HealthCheckConsensusRound.make[F, A, B](key, roundId, initialPeers, driver, gossip, selfId)
      }
      .flatMap { round =>
        allRounds.modify {
          case ConsensusRounds(historical, inProgress) =>
            def inProgressRound = inProgress.get(key)
            def historicalRound = historical.find(_.roundId == roundId)

            historicalRound
              .orElse(inProgressRound)
              .fold {
                (ConsensusRounds(historical, inProgress + (key -> round)), round.some)
              } { _ =>
                (ConsensusRounds(historical, inProgress), none)
              }
        }
      }
      .flatTap {
        _.fold(Applicative[F].unit) { round =>
          roundsInProgress
            .map(_.view.filterKeys(_ != key))
            .flatMap { parallelRounds =>
              parallelRounds.toList.traverse {
                case (key, parallelRound) =>
                  parallelRound.getRoundIds
                    .flatMap(round.addParallelRounds(key))
                    .flatMap { _ =>
                      parallelRound.addParallelRounds(key)(Set(roundId))
                    }
              }.void
            }
        }
      }
      .flatMap {
        _.fold(Applicative[F].unit)(_.start)
      }

  def handleProposal(proposal: B, depth: Int = 1): F[Unit] =
    allRounds.get.flatMap {
      case ConsensusRounds(historical, inProgress) =>
        def inProgressRound = inProgress.get(proposal.key)
        def historicalRound = historical.find(_.roundId == proposal.roundId)

        def participate = participateInRound(proposal.key, proposal.roundId)

        inProgressRound
          .map(_.processProposal(proposal))
          .orElse {
            historicalRound.map(handleProposalForHistoricalRound(proposal))
          }
          .getOrElse {
            if (depth > 0)
              participate.flatMap(_ => handleProposal(proposal, depth - 1))
            else
              (new Throwable("Unexpected recursion!")).raiseError[F, Unit] // Note: custom error type
          }
    }

  def handleProposalForHistoricalRound(proposal: B)(round: HistoricalRound): F[Unit] =
    Applicative[F].unit

  protected def onNegativeOutcome(
    peers: ConsensusRounds.Outcome[F, A, B]
  ): F[Unit] =
    peers.keys.toList.traverse { key =>
      clusterStorage.removePeer(key.id)
    }.void

  protected def onNonNegativeOutcome(
    peers: ConsensusRounds.Outcome[F, A, B]
  ): F[Unit] = Applicative[F].unit

  private def calculateOutcome(rounds: ConsensusRounds.Finished[F, A, B]): F[ConsensusRounds.Outcome[F, A, B]] =
    if (rounds.isEmpty)
      Map.empty[HealthCheckKey, (HealthCheckConsensusDecision, HealthCheckConsensusRound[F, A, B])].pure[F]
    else
      rounds.toList.traverse {
        case (key, round) =>
          round.calculateOutcome
            .map(outcome => (key, (outcome, round)))
      }.map(_.toMap)

  private def partition(
    rounds: ConsensusRounds.InProgress[F, A, B]
  ): F[(ConsensusRounds.Finished[F, A, B], ConsensusRounds.InProgress[F, A, B])] = {
    def ignoreTupleRight[X, Y, _](m: Map[X, (Y, _)]): Map[X, Y] = m.view.mapValues(_._1).toMap

    rounds.toList.traverse {
      case (key, consensus) => consensus.isFinished.map(finished => (key, (consensus, finished)))
    }.map(_.toMap.partition { case (_, (_, finished)) => finished })
      .map(_.bimap(ignoreTupleRight, ignoreTupleRight))
  }

}
