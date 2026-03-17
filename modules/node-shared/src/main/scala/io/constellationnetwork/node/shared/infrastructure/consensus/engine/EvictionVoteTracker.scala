package io.constellationnetwork.node.shared.infrastructure.consensus.engine

import cats.effect.kernel.{Async, Ref}
import cats.syntax.all._

import io.constellationnetwork.schema.peer.PeerId

/** Tracks local eviction votes from peers to enable deterministic, consensus-agreed peer removal.
  *
  * ==Problem==
  *
  * Currently, peer eviction is based on local gossip state (which peers haven't declared). Different nodes may see different sets of
  * "missing" peers at the same point in time, leading to non-deterministic eviction decisions. This can cause facilitator set divergence
  * across nodes.
  *
  * ==Solution (Scaffolding)==
  *
  * This tracker collects local eviction votes — each node votes to evict peers it considers unresponsive. When a supermajority of nodes
  * agree on the same eviction target, the eviction is considered deterministic (all honest nodes will agree).
  *
  * ==Current Behavior==
  *
  * This is scaffolding for future deterministic eviction. The tracker:
  *   - Accepts eviction votes from the local node
  *   - Tracks which peers have been voted for eviction and by how many distinct voters
  *   - Provides a query method to check if a peer has supermajority eviction votes
  *   - Integrates with the existing quality tracking for observability
  *
  * ==Future Work==
  *
  * To make eviction fully deterministic:
  *   1. Spread eviction votes via gossip (new rumor type)
  *   2. Collect votes from all facilitators (not just local)
  *   3. Include vote tallies in the Facility declaration for consensus agreement
  *   4. Only evict when the facilitatorsHash-agreed vote tally reaches supermajority
  *
  * This scaffolding prepares the local tracking infrastructure so the gossip protocol can be added incrementally.
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
