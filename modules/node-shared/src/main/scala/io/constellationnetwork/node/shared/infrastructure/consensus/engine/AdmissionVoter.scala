package io.constellationnetwork.node.shared.infrastructure.consensus.engine

import cats.Applicative

import io.constellationnetwork.node.shared.infrastructure.consensus.declaration.AdmissionReason
import io.constellationnetwork.schema.peer.PeerId

/** Emits (signs, stores locally, gossips) an `AdmissionVote` on behalf of this node (B2).
  *
  * Mirror of [[EvictionVoter]] — this node votes that `target` (previously removed from the committee) is currently observed at tip and
  * should be re-admitted. The generic engine-level emission-gate logic (peer is in the current round's `readmissionCountdown`, has been
  * observed sending a Facility this round, per-round cap) lives in the caller.
  */
trait AdmissionVoter[F[_], Key] {
  def emitAdmissionVote(
    key: Key,
    target: PeerId,
    reason: AdmissionReason
  ): F[Unit]
}

object AdmissionVoter {

  /** No-op voter: used when layer-specific gossip wiring is not yet available (tests, bootstrap paths). */
  def noop[F[_]: Applicative, Key]: AdmissionVoter[F, Key] = new AdmissionVoter[F, Key] {
    def emitAdmissionVote(
      key: Key,
      target: PeerId,
      reason: AdmissionReason
    ): F[Unit] = Applicative[F].unit
  }
}
