package io.constellationnetwork.node.shared.infrastructure.consensus.engine

import cats.Applicative

import io.constellationnetwork.node.shared.infrastructure.consensus.declaration.AdmissionReason
import io.constellationnetwork.schema.peer.PeerId

/** Emits (signs, stores locally, gossips) an `AdmissionVote` on behalf of this node (B2).
  *
  * Mirror of [[EvictionVoter]]. The caller keeps two evidence lanes distinct:
  *   - open expansion requires the proposal-carried nominee to return the exact parent from a fresh direct probe and to have sent a
  *     Facility bound to the voter's current round;
  *   - penalty/probation recovery requires its configured streak of fresh exact-parent responses, but cannot require a Facility because a
  *     peer is deliberately prevented from facilitating until its certificate clears probation.
  *
  * Per-round budgets and local evidence govern vote emission only; a quorum-certified `AdmissionCertificate` is membership authority.
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
