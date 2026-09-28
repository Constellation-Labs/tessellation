package io.constellationnetwork.node.shared.domain.healthcheck

import io.constellationnetwork.schema.peer.{Peer, PeerId}

trait LocalHealthcheck[F[_]] {

  /** Demoting check loop for `peer`, bound to `peer.session`: it is acquired only while that exact session is the recorded Responsive one,
    * every mark/removal it performs is a compare-and-set on that session, and it retires without acting on (or cancelling) a successor
    * session's record or worker once superseded. A worker already bound to the same session is joined; one bound to an older session is
    * replaced; a slot already held for a newer session rejects the request (its record is stale). Each acquisition has its own identity: a
    * retiring worker releases only its own slot, never a successor's, even one bound to the same session.
    */
  def start(peer: Peer): F[Unit]

  /** Cancel whatever worker currently holds the slot for `peerId` (the ordinary join handshake does this after installing a fresh record).
    */
  def cancel(peerId: PeerId): F[Unit]

  /** Non-demoting single check used by the isolation repair (B1'). Unlike `start`, it never marks a currently Responsive peer Unresponsive
    * before evidence: one `/session` round trip decides. The record captured from cluster storage at entry is the one queried and the one
    * every mutation is bound to (compare-and-set on its session): a healthy answer with that session restores/keeps Responsive; a differing
    * session is handled session-conditionally; a record replaced while the check was in flight is reported `Superseded` and left alone;
    * only a failed check on a still-current Responsive record hands the peer to the ordinary loop bound to that record's session (which
    * then demotes with evidence and retries with backoff, every mutation session-conditional). If an ordinary worker already exists for the
    * recorded session, the call joins it (no second fiber) and reports `Joined`.
    */
  def recheck(peer: Peer): F[PeerRecheckOutcome]
}

/** Outcome of `LocalHealthcheck.recheck`. A top-level type (not a companion member) so the domain trait and the infrastructure object can
  * keep sharing the `LocalHealthcheck` name across namespaces.
  */
sealed abstract class PeerRecheckOutcome(val label: String)

object PeerRecheckOutcome {

  /** An ordinary healthcheck fiber is already running for this peer; nothing was spawned. */
  case object Joined extends PeerRecheckOutcome("joined")

  /** The peer answered with its recorded session; it is (now) Responsive. */
  case object Healthy extends PeerRecheckOutcome("healthy")

  /** The peer answered with a different session. `removed` is true when the recorded session was still the compared one and the record was
    * removed (compare-and-set); false when a newer session had been installed concurrently and was left untouched.
    */
  final case class SessionChanged(removed: Boolean) extends PeerRecheckOutcome("session_changed")

  /** The check failed. `demotionStarted` is true when the peer was Responsive and has been handed to the ordinary check loop. */
  final case class Unreachable(demotionStarted: Boolean) extends PeerRecheckOutcome("unreachable")

  /** The record queried was replaced (new session) while the check was in flight; nothing was changed. */
  case object Superseded extends PeerRecheckOutcome("superseded")

  /** The peer is not in cluster storage. */
  case object Unknown extends PeerRecheckOutcome("unknown")
}
