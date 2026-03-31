package io.constellationnetwork.node.shared.infrastructure.consensus.engine

import cats.effect.kernel.{Async, Ref}
import cats.syntax.all._

import io.constellationnetwork.schema.peer.PeerId

/** Tracks eviction votes to coordinate peer removal during stall detection.
  *
  * Used by [[StallDetector]] to record which peers the local node considers unresponsive. Each vote maps a voter (the detecting node) to a
  * target (the unresponsive peer). The supermajority check gates eviction decisions — a peer is only evicted when enough facilitators
  * independently agree it is missing.
  *
  * Votes are cleared at the start of each consensus round via [[clearVotes]].
  *
  * ==Current Scope==
  *
  * Votes are currently local-only (each node tracks its own observations). This is sufficient because stall detection runs independently on
  * each node against the same gossip state, producing convergent eviction decisions in practice.
  *
  * ==Future: Gossip-Based Votes==
  *
  * For fully deterministic eviction across all nodes:
  *   1. Spread eviction votes via gossip (new rumor type) 2. Collect votes from all facilitators, not just local observations 3. Include
  *      vote tallies in Facility declarations for consensus agreement 4. Only evict when the consensus-agreed tally reaches supermajority
  */
trait EvictionVoteTracker[F[_]] {

  /** Record a local eviction vote for a peer. Called when the local node detects a peer is unresponsive. */
  def voteToEvict(voter: PeerId, target: PeerId): F[Unit]

  /** Get all peers that have received eviction votes, with their vote counts. */
  def getEvictionVotes: F[Map[PeerId, Set[PeerId]]]

  /** Check if a peer has supermajority eviction votes (>= threshold of total facilitators). */
  def hasSupermajorityVotes(target: PeerId, totalFacilitators: Int, threshold: Double): F[Boolean]

  /** Clear all eviction votes. Should be called at the start of each round. */
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

        def getEvictionVotes: F[Map[PeerId, Set[PeerId]]] =
          votesRef.get

        def hasSupermajorityVotes(target: PeerId, totalFacilitators: Int, threshold: Double): F[Boolean] =
          votesRef.get.map { votes =>
            val voterCount = votes.getOrElse(target, Set.empty).size
            val required = math.ceil(totalFacilitators * threshold).toInt
            voterCount >= required
          }

        def clearVotes: F[Unit] =
          votesRef.set(Map.empty)
      }
    }
}
