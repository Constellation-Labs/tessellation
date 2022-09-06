package org.tessellation.sdk.domain.healthcheck.consensus

import cats.effect._
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.contravariantSemigroupal._
import cats.syntax.eq._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.option._
import cats.syntax.show._
import cats.{Applicative, Show}

import scala.concurrent.duration._
import scala.reflect.runtime.universe.TypeTag

import org.tessellation.schema.node.NodeState
import org.tessellation.schema.peer.{Peer, PeerId}
import org.tessellation.sdk.config.types.HealthCheckConfig
import org.tessellation.sdk.domain.cluster.storage.ClusterStorage
import org.tessellation.sdk.domain.gossip.Gossip
import org.tessellation.sdk.domain.healthcheck.consensus.HealthCheckConsensusRound._
import org.tessellation.sdk.domain.healthcheck.consensus.types._

import org.typelevel.log4cats.slf4j.Slf4jLogger

class HealthCheckConsensusRound[F[_]: Async, K <: HealthCheckKey: Show, A <: HealthCheckStatus, B <: ConsensusHealthStatus[
  K,
  A
]: TypeTag, C <: HealthCheckConsensusDecision](
  key: K,
  initialRoundIds: Set[HealthCheckRoundId],
  driver: HealthCheckConsensusDriver[K, A, B, C],
  config: HealthCheckConfig,
  startedAt: FiniteDuration,
  ownStatus: Fiber[F, Throwable, A],
  statusOnError: A,
  peers: Ref[F, Set[PeerId]],
  roundIds: Ref[F, Set[HealthCheckRoundId]],
  proposals: Ref[F, Map[PeerId, B]],
  parallelRounds: Ref[F, Map[K, Set[HealthCheckRoundId]]],
  sentProposal: Ref[F, Boolean],
  gossip: Gossip[F],
  clusterStorage: ClusterStorage[F],
  selfId: PeerId
) {

  def logger = Slf4jLogger.getLogger[F]

  def start: F[Unit] =
    Spawn[F].start {
      sendProposal
        .handleErrorWith(err =>
          logger.error(err)(
            s"An error occurred while sending the healthcheck proposal for initialRoundIds=${initialRoundIds.show} for key=${key.show}"
          )
        )
        .flatTap { _ =>
          logger.info(s"HealthCheck round started with initialRoundIds=${initialRoundIds.show} for key=${key.show}")
        }
    }.void

  def getPeers: F[Set[PeerId]] = peers.get

  def managePeers(currentPeers: Set[Peer]): F[Unit] = {
    def absentPeers = NodeState.absent(currentPeers).map(_.id)

    proposals.get.flatMap { received =>
      peers.update { roundPeers =>
        def missingPeers = roundPeers -- currentPeers.map(_.id)
        def toRemove = (absentPeers ++ missingPeers) -- received.keySet

        roundPeers -- toRemove
      }
    }
  }

  def hasProposal(proposal: B): F[HasProposal] =
    proposals.get.map(_.get(proposal.owner)).map {
      _.fold[HasProposal](ProposalAndOwnerDoesNotExist) { p =>
        if (p == proposal) SameProposalExists else OwnersProposalExists
      }
    }

  def isFinished: F[Boolean] = allProposalsReceived.flatMap { received =>
    sentProposal.get.map(_ && received)
  }

  def elapsed: F[FiniteDuration] =
    Clock[F].monotonic.map(_ - startedAt)

  def processProposal(proposal: B): F[Unit] =
    if (proposal.key == key) {
      proposals.modify { m =>
        m.get(proposal.owner)
          .fold {
            (m + (proposal.owner -> proposal), proposal.some)
          } { _ =>
            (m, none)
          }
      }.flatMap {
        _.fold(Applicative[F].unit) { proposal =>
          roundIds.update(_ ++ proposal.roundIds) >>
            peers.update(_ ++ proposal.clusterState.filterNot(p => p === selfId || p === key.id))
        }
      }
    } else Applicative[F].unit

  def getRoundIds: F[Set[HealthCheckRoundId]] = roundIds.get

  def addParallelRounds(key: K)(roundIds: Set[HealthCheckRoundId]): F[Unit] =
    parallelRounds.update { m =>
      def updated = m.get(key).fold(roundIds)(_ ++ roundIds)

      m + (key -> updated)
    }

  private def removeUnresponsiveParallelPeers(): F[Unit] =
    (parallelRounds.get, peers.get, proposals.get.map(_.keySet)).mapN { (_parallelRounds, _peers, _proposals) =>
      if (!_parallelRounds.isEmpty) {
        def missing = _parallelRounds.keySet.map(_.id).intersect(_peers -- _proposals)

        peers.update(_ -- missing) >>
          Applicative[F].whenA(missing.size > 0) {
            getRoundIds.flatMap { ids =>
              logger.debug(s"Removed unresponsive parallel peers: ${missing.show} for round: ${ids.show}")
            }
          }
      } else Applicative[F].unit
    }.flatten

  def missingProposals: F[Set[PeerId]] =
    proposals.get.flatMap { _proposals =>
      peers.get.map { _peers =>
        _peers -- _proposals.keySet
      }
    }

  def calculateOutcome: F[C] =
    status.flatMap { _status =>
      (proposals.get, peers.get).mapN { (_proposals, _peers) =>
        def received = _proposals.view.filterKeys(_peers.contains).values.toList

        driver.calculateConsensusOutcome(key, _status, selfId, received)
      }
    }

  def manage: F[Unit] =
    elapsed.flatMap { e =>
      if (driver.removePeersWithParallelRound && e >= config.removeUnresponsiveParallelPeersAfter) {
        removeUnresponsiveParallelPeers()
      } else Applicative[F].unit
    }

  def generateHistoricalData(ownProposal: B, decision: C): F[HistoricalRound[K, A, B]] =
    roundIds.get.map(HistoricalRound(key, _, ownProposal, decision))

  def ownConsensusHealthStatus: F[B] =
    clusterStorage.getPeers.map(_.map(_.id)).flatMap { clusterState =>
      getRoundIds.flatMap { ids =>
        status.map {
          driver.consensusHealthStatus(key, _, ids, selfId, clusterState)
        }
      }
    }

  private def status: F[A] =
    ownStatus.join.flatMap {
      case Outcome.Succeeded(fa) => fa
      case Outcome.Errored(_)    => statusOnError.pure[F]
      case Outcome.Canceled()    => statusOnError.pure[F]
    }

  private def sendProposal: F[Unit] =
    ownConsensusHealthStatus.flatMap { status =>
      gossip.spread(status)
    }.flatTap { _ =>
      sentProposal.set(true)
    }

  private def allProposalsReceived: F[Boolean] =
    (peers.get, proposals.get.map(_.keySet))
      .mapN(_ -- _)
      .map(_.isEmpty)
}

object HealthCheckConsensusRound {

  def make[F[_]: Async, K <: HealthCheckKey: Show, A <: HealthCheckStatus, B <: ConsensusHealthStatus[
    K,
    A
  ]: TypeTag, C <: HealthCheckConsensusDecision](
    key: K,
    initialRoundIds: Set[HealthCheckRoundId],
    initialPeers: Set[PeerId],
    ownStatus: Fiber[F, Throwable, A],
    statusOnError: A,
    driver: HealthCheckConsensusDriver[K, A, B, C],
    config: HealthCheckConfig,
    gossip: Gossip[F],
    clusterStorage: ClusterStorage[F],
    selfId: PeerId
  ): F[HealthCheckConsensusRound[F, K, A, B, C]] = {

    def mkStartedAt = Clock[F].monotonic
    def mkPeers = Ref.of[F, Set[PeerId]](initialPeers)
    def mkRoundIds = Ref.of[F, Set[HealthCheckRoundId]](initialRoundIds)
    def mkProposals = Ref.of[F, Map[PeerId, B]](Map.empty)
    def mkParallelRounds = Ref.of[F, Map[K, Set[HealthCheckRoundId]]](Map.empty)
    def mkSentProposal = Ref.of[F, Boolean](false)

    (mkStartedAt, mkPeers, mkRoundIds, mkProposals, mkParallelRounds, mkSentProposal).mapN {
      (startedAt, peers, roundIds, proposals, parallelRounds, sentProposal) =>
        new HealthCheckConsensusRound[F, K, A, B, C](
          key,
          initialRoundIds,
          driver,
          config,
          startedAt,
          ownStatus,
          statusOnError,
          peers,
          roundIds,
          proposals,
          parallelRounds,
          sentProposal,
          gossip,
          clusterStorage,
          selfId
        )
    }
  }

  trait HasProposal
  case object ProposalAndOwnerDoesNotExist extends HasProposal
  case object SameProposalExists extends HasProposal
  case object OwnersProposalExists extends HasProposal

}
