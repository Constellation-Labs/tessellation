package io.constellationnetwork.node.shared.domain.healthcheck

import io.constellationnetwork.schema.peer.{Peer, PeerId}

trait LocalHealthcheck[F[_]] {
  def start(peer: Peer): F[Unit]
  def cancel(peerId: PeerId): F[Unit]

  /** Non-demoting single check used by the isolation repair (B1'). Unlike `start`, it never marks a currently Responsive peer Unresponsive
    * before evidence: one `/session` round trip decides. The record captured from cluster storage at entry is the one queried and the one
    * every mutation is bound to (compare-and-set on its session): a healthy answer with that session restores/keeps Responsive; a differing
    * session is handled session-conditionally; a record replaced while the check was in flight is reported `Superseded` and left alone;
    * only a failed check on a still-current Responsive record hands the peer to the ordinary `start` loop (which then demotes with evidence
    * and retries with backoff). If an ordinary check fiber already exists for the peer, the call joins it (no second fiber) and reports
    * `Joined`.
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
