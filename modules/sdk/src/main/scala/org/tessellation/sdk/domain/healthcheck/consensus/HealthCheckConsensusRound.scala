package org.tessellation.sdk.domain.healthcheck.consensus

import cats.Applicative
import cats.effect._
import cats.syntax.applicative._
import cats.syntax.contravariantSemigroupal._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.option._

import scala.concurrent.duration._
import scala.reflect.runtime.universe.TypeTag

import org.tessellation.schema.node.NodeState
import org.tessellation.schema.peer.{Peer, PeerId}
import org.tessellation.sdk.config.types.HealthCheckConfig
import org.tessellation.sdk.domain.gossip.Gossip
import org.tessellation.sdk.domain.healthcheck.consensus.types._

import org.typelevel.log4cats.slf4j.Slf4jLogger

class HealthCheckConsensusRound[F[_]: Async, K <: HealthCheckKey, A <: HealthCheckStatus, B <: ConsensusHealthStatus[
  K,
  A
]: TypeTag, C <: HealthCheckConsensusDecision](
  key: K,
  roundId: HealthCheckRoundId,
  driver: HealthCheckConsensusDriver[K, A, B, C],
  config: HealthCheckConfig,
  startedAt: FiniteDuration,
  ownStatus: Fiber[F, Throwable, A],
  statusOnError: A,
  peers: Ref[F, Set[PeerId]],
  roundIds: Ref[F, Set[HealthCheckRoundId]],
  proposals: Ref[F, Map[PeerId, B]],
  parallelRounds: Ref[F, Map[K, Set[HealthCheckRoundId]]],
  gossip: Gossip[F],
  selfId: PeerId
) {

  def logger = Slf4jLogger.getLogger[F]

  def start: F[Unit] =
    Spawn[F].start {
      sendProposal.flatTap { _ =>
        logger.info(s"HealthCheck round started with roundId=$roundId for peer=${key.id}")
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

  def isFinished: F[Boolean] = allProposalsReceived

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
        _.fold { Applicative[F].unit } { proposal =>
          roundIds.update(_ + proposal.roundId).flatMap { _ =>
            peers.update(_ + proposal.owner)
          }
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

        peers.update(_ -- missing)
      } else Applicative[F].unit
    }.flatten

  def calculateOutcome: F[C] =
    status.flatMap { _status =>
      (proposals.get, peers.get).mapN { (_proposals, _peers) =>
        def received = _proposals.view.filterKeys(_peers.contains).values.toList

        driver.calculateConsensusOutcome(key, _status, selfId, received)
      }
    }

  def manage: F[Unit] =
    Clock[F].monotonic.map(_ - startedAt).flatMap { elapsed =>
      sendProposal.flatMap { _ =>
        if (driver.removePeersWithParallelRound && elapsed >= config.removeUnresponsiveParallelPeersAfter) {
          removeUnresponsiveParallelPeers()
        } else Applicative[F].unit
      }
    }

  def generateHistoricalData(decision: C): F[HistoricalRound[K]] =
    HistoricalRound(key, roundId, decision).pure[F]

  def ownConsensusHealthStatus: F[B] =
    status.map {
      driver.consensusHealthStatus(key, _, roundId, selfId)
    }

  private def status: F[A] =
    ownStatus.join.flatMap {
      case Outcome.Succeeded(fa) => fa
      case Outcome.Errored(_)    => statusOnError.pure[F]
      case Outcome.Canceled()    => statusOnError.pure[F]
    }

  private def sendProposal: F[Unit] =
    ownConsensusHealthStatus.flatMap(status => gossip.spreadCommon(status))

  private def allProposalsReceived: F[Boolean] =
    (peers.get, proposals.get.map(_.keySet))
      .mapN(_ -- _)
      .map(_.isEmpty)
}

object HealthCheckConsensusRound {

  def make[F[_]: Async, K <: HealthCheckKey, A <: HealthCheckStatus, B <: ConsensusHealthStatus[K, A]: TypeTag, C <: HealthCheckConsensusDecision](
    key: K,
    roundId: HealthCheckRoundId,
    initialPeers: Set[PeerId],
    ownStatus: Fiber[F, Throwable, A],
    statusOnError: A,
    driver: HealthCheckConsensusDriver[K, A, B, C],
    config: HealthCheckConfig,
    gossip: Gossip[F],
    selfId: PeerId
  ): F[HealthCheckConsensusRound[F, K, A, B, C]] = {

    def mkStartedAt = Clock[F].monotonic
    def mkPeers = Ref.of[F, Set[PeerId]](initialPeers)
    def mkRoundIds = Ref.of[F, Set[HealthCheckRoundId]](Set(roundId))
    def mkProposals = Ref.of[F, Map[PeerId, B]](Map.empty)
    def mkParallelRounds = Ref.of[F, Map[K, Set[HealthCheckRoundId]]](Map.empty)

    (mkStartedAt, mkPeers, mkRoundIds, mkProposals, mkParallelRounds).mapN {
      (startedAt, peers, roundIds, proposals, parallelRounds) =>
        new HealthCheckConsensusRound[F, K, A, B, C](
          key,
          roundId,
          driver,
          config,
          startedAt,
          ownStatus,
          statusOnError,
          peers,
          roundIds,
          proposals,
          parallelRounds,
          gossip,
          selfId
        )
    }
  }
}
