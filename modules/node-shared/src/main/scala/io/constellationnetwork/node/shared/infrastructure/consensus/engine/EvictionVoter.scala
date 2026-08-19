package io.constellationnetwork.node.shared.infrastructure.consensus.engine

import cats.Applicative

import io.constellationnetwork.node.shared.infrastructure.consensus.declaration.EvictionReason
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash

/** Emits (signs, stores locally, gossips) an `EvictionVote` on behalf of this node.
  *
  * Abstraction over the layer-specific (dag-l0 / currency-l0) wiring that needs `KeyPair`, `Gossip`, and `HasherSelector` to produce a
  * properly-signed `Signed[EvictionVote]`. The generic engine-level emission-gate logic (check committee membership, per-round cap, cluster
  * storage responsiveness) lives in the caller (typically `StallDetector`); this trait's responsibility is solely the sign+store+gossip
  * step once the decision has been made.
  *
  * Parallels `ViewChangeVoter` / `GossipingViewChangeVoter`.
  */
trait EvictionVoter[F[_], Key] {
  def emitEvictionVote(
    key: Key,
    target: PeerId,
    reason: EvictionReason
  ): F[Unit]

  /** Emit during round creation, before the new [[io.constellationnetwork.node.shared.infrastructure.consensus.state.ConsensusState]] is
    * visible in storage. The default preserves compatibility with voters that do not need explicit round context; the gossiping voter
    * overrides it so the vote is bound to the same frozen committee and parent hash as the state being created.
    */
  def emitEvictionVoteForRound(
    key: Key,
    target: PeerId,
    reason: EvictionReason,
    roundStartFacilitators: List[PeerId],
    gossipFacilitators: List[PeerId],
    lastSnapshotHash: Hash
  ): F[Unit] = emitEvictionVote(key, target, reason)
}

object EvictionVoter {

  /** No-op voter: used when layer-specific gossip wiring is not yet available (tests, bootstrap paths). Safe to call; does nothing. */
  def noop[F[_]: Applicative, Key]: EvictionVoter[F, Key] = new EvictionVoter[F, Key] {
    def emitEvictionVote(
      key: Key,
      target: PeerId,
      reason: EvictionReason
    ): F[Unit] = Applicative[F].unit
  }
}
