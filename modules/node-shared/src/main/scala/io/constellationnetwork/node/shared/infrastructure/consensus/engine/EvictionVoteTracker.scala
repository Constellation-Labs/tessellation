package io.constellationnetwork.node.shared.infrastructure.consensus.engine

import cats.effect.kernel.{Async, Ref}
import cats.syntax.all._

import io.constellationnetwork.schema.peer.PeerId

/** Tracks eviction votes to coordinate peer removal during stall detection.
  *
  * Used by [[StallDetector]] to record which peers are considered unresponsive. Votes come from two sources:
  *   1. '''Local detection''': The local node's StallDetector calls [[voteToEvict]] when it observes missing peers
  *   1. '''Remote reports''': Other facilitators broadcast `StallReport` declarations via gossip, which are registered via
  *      [[registerRemoteVotes]]
  *
  * Eviction only proceeds when [[getMajorityEvictionTargets]] returns a non-empty set — meaning a majority of facilitators independently
  * agree a peer is missing. This makes eviction consensus-agreed rather than unilateral.
  *
  * Votes are cleared at the start of each consensus round via [[clearVotes]].
  */
trait EvictionVoteTracker[F[_]] {

  /** Record a local eviction vote for a peer. Called when the local node detects a peer is unresponsive. */
  def voteToEvict(voter: PeerId, target: PeerId): F[Unit]

  /** Register eviction votes from a remote peer's StallReport declaration. */
  def registerRemoteVotes(voter: PeerId, targets: Set[PeerId]): F[Unit]

  /** Get all peers that have received eviction votes, with their voter sets. */
  def getEvictionVotes: F[Map[PeerId, Set[PeerId]]]

  /** Check if a peer has supermajority eviction votes (>= threshold of total facilitators). */
  def hasSupermajorityVotes(target: PeerId, totalFacilitators: Int, threshold: Double): F[Boolean]

  /** Returns peers that have received votes from a strict majority (>50%) of facilitators. */
  def getMajorityEvictionTargets(totalFacilitators: Int): F[Set[PeerId]]

  /** Clear all eviction votes. Should be called at the start of each round or when phase advances. */
  def clearVotes: F[Unit]
}

object EvictionVoteTracker {

  def make[F[_]: Async]: F[EvictionVoteTracker[F]] =
    Ref.of[F, Map[PeerId, Set[PeerId]]](Map.empty).map { votesRef =>
      new EvictionVoteTracker[F] {

        def voteToEvict(voter: PeerId, target: PeerId): F[Unit] =
          votesRef.update { votes =>
            val currentVoters = votes.getOrElse(target, Set.empty)
            votes.updated(target, currentVoters + voter)
          }

        def registerRemoteVotes(voter: PeerId, targets: Set[PeerId]): F[Unit] =
          votesRef.update { votes =>
            targets.foldLeft(votes) { (acc, target) =>
              val currentVoters = acc.getOrElse(target, Set.empty)
              acc.updated(target, currentVoters + voter)
            }
          }

        def getEvictionVotes: F[Map[PeerId, Set[PeerId]]] =
          votesRef.get

        def hasSupermajorityVotes(target: PeerId, totalFacilitators: Int, threshold: Double): F[Boolean] =
          votesRef.get.map { votes =>
            val voterCount = votes.getOrElse(target, Set.empty).size
            val required = math.ceil(totalFacilitators * threshold).toInt
            voterCount >= required
          }

        def getMajorityEvictionTargets(totalFacilitators: Int): F[Set[PeerId]] =
          votesRef.get.map { votes =>
            val required = (totalFacilitators / 2) + 1
            votes.collect {
              case (target, voters) if voters.size >= required => target
            }.toSet
          }

        def clearVotes: F[Unit] =
          votesRef.set(Map.empty)
      }
    }
}
