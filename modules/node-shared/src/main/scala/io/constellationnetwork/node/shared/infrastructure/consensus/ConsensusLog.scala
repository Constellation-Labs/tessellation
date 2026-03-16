package io.constellationnetwork.node.shared.infrastructure.consensus

import cats.Applicative
import cats.syntax.all._

import io.constellationnetwork.schema.peer.PeerId

import org.typelevel.log4cats.SelfAwareStructuredLogger

/** Thin structured-logging helper for consensus.
  *
  * Every log line produced by this helper has the form:
  * {{{
  *   [CONSENSUS:<category>] round=<key> role=<role> event=<event> k1=v1 k2=v2 ...
  * }}}
  *
  * This makes it trivial to:
  *   - `grep 'round=42'` to see every log for one consensus round
  *   - `grep 'role=Leader'` to isolate leader-only activity
  *   - `grep 'event=VALIDATION_FAILED'` to find fork-triggering mismatches
  *
  * ==Usage==
  * {{{
  *   ConsensusLog.info(logger, ConsensusLog.Phase, key.show, role,
  *     "event" -> "FACILITIES_TO_PROPOSALS",
  *     "trigger" -> trigger.show,
  *     "facilitators" -> facilitators.size.toString)
  * }}}
  *
  * ==Design==
  *
  * Stateless, pure formatting. No implicits beyond `Applicative[F]` for the logger. Accepts only `(String, String)*` pairs to prevent
  * accidentally logging large objects (artifacts, contexts, full state).
  */
object ConsensusLog {

  // ── Log categories ──────────────────────────────────────────────
  val Lifecycle: String = "LIFECYCLE"
  val Phase: String = "PHASE"
  val Stall: String = "STALL"
  val Quorum: String = "QUORUM"
  val Fork: String = "FORK"
  val Facilitator: String = "FACILITATOR"
  val Proposal: String = "PROPOSAL"
  val Validation: String = "VALIDATION"

  // ── Formatting ──────────────────────────────────────────────────

  /** Build a structured log line.
    *
    * @param category
    *   One of the constants above (e.g. `Lifecycle`, `Phase`)
    * @param round
    *   The consensus round key (ordinal), or `"n/a"` when not yet known
    * @param role
    *   `"Leader"`, `"Validator"`, or `"n/a"`
    * @param pairs
    *   Additional key=value pairs (event, trigger, hash, etc.)
    * @return
    *   Formatted string like `[CONSENSUS:LIFECYCLE] round=42 role=Leader event=ROUND_STARTED ...`
    */
  def format(category: String, round: String, role: String, pairs: (String, String)*): String = {
    val sb = new StringBuilder(128)
    sb.append("[CONSENSUS:").append(category).append("] round=").append(round).append(" role=").append(role)
    pairs.foreach { case (k, v) => sb.append(' ').append(k).append('=').append(v) }
    sb.toString
  }

  // ── Convenience loggers ─────────────────────────────────────────

  def info[F[_]: Applicative](
    logger: SelfAwareStructuredLogger[F],
    category: String,
    round: String,
    role: String,
    pairs: (String, String)*
  ): F[Unit] =
    logger.info(format(category, round, role, pairs: _*))

  def warn[F[_]: Applicative](
    logger: SelfAwareStructuredLogger[F],
    category: String,
    round: String,
    role: String,
    pairs: (String, String)*
  ): F[Unit] =
    logger.warn(format(category, round, role, pairs: _*))

  def debug[F[_]: Applicative](
    logger: SelfAwareStructuredLogger[F],
    category: String,
    round: String,
    role: String,
    pairs: (String, String)*
  ): F[Unit] =
    logger.debug(format(category, round, role, pairs: _*))

  def error[F[_]: Applicative](
    logger: SelfAwareStructuredLogger[F],
    category: String,
    round: String,
    role: String,
    pairs: (String, String)*
  ): F[Unit] =
    logger.error(format(category, round, role, pairs: _*))

  // ── Helpers ─────────────────────────────────────────────────────

  /** Determine the node's role based on whether it is the current leader. */
  def role(selfId: PeerId, leader: PeerId): String =
    if (selfId == leader) "Leader" else "Validator"

  /** Truncated peer ID for log display (first 8 hex chars). */
  def pid(p: PeerId): String = p.show.take(8)
}
