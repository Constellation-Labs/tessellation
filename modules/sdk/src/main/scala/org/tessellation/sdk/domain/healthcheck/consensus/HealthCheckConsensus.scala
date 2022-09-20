package org.tessellation.sdk.domain.healthcheck.consensus

import cats.data.OptionT
import cats.effect._
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.bifunctor._
import cats.syntax.eq._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.option._
import cats.syntax.show._
import cats.syntax.traverse._
import cats.{Applicative, Show}

import scala.reflect.runtime.universe.TypeTag

import org.tessellation.effects.GenUUID
import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.config.types.HealthCheckConfig
import org.tessellation.sdk.domain.cluster.storage.ClusterStorage
import org.tessellation.sdk.domain.gossip.Gossip
import org.tessellation.sdk.domain.healthcheck.consensus.HealthCheckConsensusRound
import org.tessellation.sdk.domain.healthcheck.consensus.types.ConsensusRounds.InProgress
import org.tessellation.sdk.domain.healthcheck.consensus.types._
import org.tessellation.sdk.domain.healthcheck.consensus.types.types.RoundId
import org.tessellation.sdk.domain.healthcheck.services.HealthCheck

import io.circe.Encoder
import org.typelevel.log4cats.slf4j.Slf4jLogger

abstract class HealthCheckConsensus[
  F[_]: Async: GenUUID,
  K <: HealthCheckKey: Show,
  A <: HealthCheckStatus,
  B <: ConsensusHealthStatus[K, A]: TypeTag: Encoder,
  C <: HealthCheckConsensusDecision
](
  clusterStorage: ClusterStorage[F],
  selfId: PeerId,
  driver: HealthCheckConsensusDriver[K, A, B, C],
  gossip: Gossip[F],
  config: HealthCheckConfig,
  waitingProposals: Ref[F, Set[B]]
) extends HealthCheck[F] {
  def logger = Slf4jLogger.getLogger[F]

  def allRounds: Ref[F, ConsensusRounds[F, K, A, B, C]]

  def ownStatus(key: K): F[Fiber[F, Throwable, A]]

  def statusOnError(key: K): A

  def periodic: F[Unit]

  def onOutcome(outcomes: ConsensusRounds.Outcome[F, K, A, B, C]): F[Unit]

  def requestProposal(peer: PeerId, roundIds: Set[HealthCheckRoundId], ownProposal: B): F[Option[B]]

  def roundsInProgress: F[InProgress[F, K, A, B, C]] = allRounds.get.map(_.inProgress)
  def historicalRounds: F[List[HistoricalRound[K, A, B]]] = allRounds.get.map(_.historical)

  def peersUnderConsensus: F[Set[PeerId]] =
    roundsInProgress.map(_.keySet.map(_.id))

  def getOwnProposal(roundIds: Set[HealthCheckRoundId]): F[Option[B]] = {
    def inProgress =
      roundsInProgress.flatMap { r =>
        r.values.toList.traverse { round =>
          round.getRoundIds.map { ids =>
            (round, ids)
          }
        }
      }.flatMap { r =>
        r.find {
          case (_, ids) => ids.intersect(roundIds).nonEmpty
        }.map(_._1).traverse(_.ownConsensusHealthStatus)
      }

    def historical = historicalRounds.map { r =>
      r.find(_.roundIds.intersect(roundIds).nonEmpty).map(_.ownProposal)
    }

    OptionT(historical).orElseF(inProgress).value
  }

  final override def trigger(): F[Unit] =
    periodic >> triggerRound()

  private def triggerRound(): F[Unit] =
    roundsInProgress.flatMap { inProgress =>
      if (inProgress.nonEmpty) manageRounds(inProgress) else Applicative[F].unit
    }

  private def manageMissingProposals(
    missingFromPeers: Set[PeerId],
    key: K,
    round: HealthCheckConsensusRound[F, K, A, B, C]
  ): F[Unit] =
    round.elapsed
      .map(_ >= config.requestProposalsAfter)
      .ifM(
        round.getRoundIds.flatMap { roundIds =>
          Applicative[F].whenA(missingFromPeers.size > 0) {
            logger.debug(
              s"Missing proposals for round ids: ${roundIds.show} for key: ${key.show} are from peers: ${missingFromPeers.show}. Requesting proposals"
            )
          } >>
            round.ownConsensusHealthStatus.flatMap { ownProposal =>
              missingFromPeers.toList.traverse { id =>
                requestProposal(id, roundIds, ownProposal).map(_.map((id, _)))
              }.map(_.flatten)
                .flatMap(_.traverse {
                  case (peerId, proposal) =>
                    Applicative[F].whenA(peerId === proposal.owner)(handleProposal(proposal))
                })
                .void
            }
        },
        Applicative[F].unit
      )

  private def manageRounds(rounds: ConsensusRounds.InProgress[F, K, A, B, C]): F[Unit] = {
    def checkRounds(inProgress: ConsensusRounds.InProgress[F, K, A, B, C]): F[ConsensusRounds.Finished[F, K, A, B, C]] =
      clusterStorage.getPeers.flatTap { peers =>
        rounds.values.toList.traverse { round =>
          round.managePeers(peers)
        }
      }.flatMap { _ =>
        partition(inProgress).flatTap {
          case (_, inProgress) =>
            inProgress.toList.traverse {
              case (key, round) =>
                round.missingProposals.flatMap(manageMissingProposals(_, key, round))
            }
        }.map { case (finished, _) => finished }
      }

    logger.info(s"Healthcheck rounds in progress: ${rounds.keySet.show}") >>
      partition(rounds).flatMap {
        case (finished, inProgress) =>
          checkRounds(inProgress).map(r => (finished ++ r, inProgress -- r.keySet))
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
        onOutcome(outcome).handleErrorWith(e => logger.error(e)("Unhandled error on outcome action. Check implementation."))
      }.flatMap {
        _.values.toList.traverse {
          case (decision, round) => round.ownConsensusHealthStatus.flatMap(round.generateHistoricalData(_, decision))
        }
      }.flatMap { finishedRounds =>
        allRounds.update {
          case ConsensusRounds(historical, inProgress) =>
            def updatedHistorical = historical ++ finishedRounds
            def updatedInProgress = inProgress -- finishedRounds.map(_.key).toSet

            ConsensusRounds(updatedHistorical, updatedInProgress)
        }
      }.flatTap { _ =>
        waitingProposals.modify { proposals =>
          (Set.empty[B], proposals.toList.traverse(handleProposal(_)))
        }.flatten
      }
  }

  protected def createRoundId: F[RoundId] = GenUUID[F].make.map(RoundId.apply)

  def startOwnRound(key: K) =
    clusterStorage
      .hasPeerId(key.id)
      .ifM(
        createRoundId.map(HealthCheckRoundId(_, selfId)).flatMap(id => startRound(key, Set(id))),
        logger.warn(s"Trying to start own round for key ${key.show}, but peer is unknown")
      )

  def participateInRound(key: K, roundIds: Set[HealthCheckRoundId]): F[Unit] =
    startRound(key, roundIds)

  def startRound(
    key: K,
    roundIds: Set[HealthCheckRoundId]
  ): F[Unit] =
    clusterStorage.getResponsivePeers
      .map(_.map(_.id))
      .map(_ - key.id)
      .flatMap { initialPeers =>
        ownStatus(key).flatMap { status =>
          HealthCheckConsensusRound.make[F, K, A, B, C](
            key,
            roundIds,
            initialPeers,
            status,
            statusOnError(key),
            driver,
            config,
            gossip,
            clusterStorage,
            selfId
          )
        }
      }
      .flatMap { round =>
        allRounds.modify {
          case ConsensusRounds(historical, inProgress) =>
            def inProgressRound = inProgress.get(key)
            def historicalRound = historical.find(_.roundIds.intersect(roundIds).nonEmpty)

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
                      parallelRound.addParallelRounds(key)(roundIds)
                    }
              }.void
            }
        }
      }
      .flatMap {
        _.fold(Applicative[F].unit)(_.start)
      }

  def handleProposal(proposal: B, depth: Int = 1): F[Unit] =
    logger.info(
      s"Received proposal for roundIds: ${proposal.roundIds.show} for key: ${proposal.key.show} with status: ${proposal.status} from peer: ${proposal.owner.show}"
    ) >>
      (if (proposal.owner === selfId || proposal.key.id === selfId)
         Applicative[F].unit
       else
         allRounds.get.flatMap {
           case ConsensusRounds(historical, inProgress) =>
             def inProgressRound = inProgress.get(proposal.key)
             def historicalRound = historical.find(_.roundIds.intersect(proposal.roundIds).nonEmpty)

             def participate = participateInRound(proposal.key, proposal.roundIds)

             inProgressRound.map { r =>
               r.hasProposal(proposal).flatMap {
                 case HealthCheckConsensusRound.ProposalAndOwnerDoesNotExist => r.processProposal(proposal)
                 case HealthCheckConsensusRound.SameProposalExists =>
                   logger.debug(s"Same proposal exists, ignoring")
                 case HealthCheckConsensusRound.OwnersProposalExists => waitingProposals.update(_ + proposal)
                 case _                                              => logger.error(s"Unpexpected state returned")
               }
             }.orElse {
               historicalRound.map(handleProposalForHistoricalRound(proposal))
             }.getOrElse {
               if (depth > 0)
                 participate.flatMap(_ => handleProposal(proposal, depth - 1))
               else
                 (new Throwable("Unexpected recursion!")).raiseError[F, Unit]
             }
         })

  def handleProposalForHistoricalRound(proposal: B)(round: HistoricalRound[K, A, B]): F[Unit] =
    Applicative[F].unit

  private def calculateOutcome(
    rounds: ConsensusRounds.Finished[F, K, A, B, C]
  ): F[ConsensusRounds.Outcome[F, K, A, B, C]] =
    if (rounds.isEmpty)
      Map.empty.pure[F]
    else
      rounds.toList.traverse {
        case (key, round) =>
          round.calculateOutcome
            .map(outcome => (key, (outcome, round)))
      }.map(_.toMap)

  private def partition(
    rounds: ConsensusRounds.InProgress[F, K, A, B, C]
  ): F[(ConsensusRounds.Finished[F, K, A, B, C], ConsensusRounds.InProgress[F, K, A, B, C])] = {
    def ignoreTupleRight[X, Y, Z](m: Map[X, (Y, Z)]): Map[X, Y] = m.view.mapValues(_._1).toMap

    rounds.toList.traverse {
      case (key, consensus) => consensus.isFinished.map(finished => (key, (consensus, finished)))
    }.map(_.toMap.partition { case (_, (_, finished)) => finished })
      .map(_.bimap(ignoreTupleRight, ignoreTupleRight))
  }

}
