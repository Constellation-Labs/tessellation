package io.constellationnetwork.node.shared.infrastructure.consensus.engine

import cats.Applicative

import io.constellationnetwork.node.shared.infrastructure.consensus.declaration.EvictionReason
import io.constellationnetwork.schema.peer.PeerId

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
