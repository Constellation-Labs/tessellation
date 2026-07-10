package io.constellationnetwork.node.shared.infrastructure.consensus.engine

import cats.effect.kernel.{Async, Ref}

import derevo.circe.magnolia.encoder
import derevo.derive

/** Observable snapshot of consensus round health.
  *
  * Updated by StallDetector on each poll cycle. Exposed via `/consensus/health` endpoint and as Prometheus gauges so operators can monitor
  * round progress without parsing logs.
  *
  * ==Fields==
  *
  * '''Round identification:'''
  *   - `key` — Current ordinal being decided
  *   - `phase` — Current phase name (CollectingFacilities, CollectingProposals, etc.)
  *   - `phaseIndex` — Numeric phase (0=facilities, 1=proposals, 2=signatures, ...)
  *
  * '''Participants:'''
  *   - `facilitatorCount` — Total facilitators in round
  *   - `declaredCount` — Peers who have submitted declaration for current phase
  *   - `activeCount` — Facilitators minus withdrawn
  *   - `leader` — Current round leader peer ID (truncated)
  *   - `viewNumber` — View change counter (0 = original leader)
  *
  * '''Timing:'''
  *   - `roundElapsedMs` — Wall-clock since round started
  *   - `phaseElapsedMs` — Wall-clock since current phase started
  *
  * '''Health:'''
  *   - `stallCount` — Number of stall cycles detected this round
  *   - `consecutiveAbandonments` — Consecutive rounds abandoned at same ordinal
  *   - `totalRecoveryAttempts` — Total recovery download attempts (resets on force leave)
  *   - `isRunning` — Whether a round is currently in progress
  */
@derive(encoder)
final case class ConsensusHealthStatus(
  key: Option[String] = None,
  phase: Option[String] = None,
  phaseIndex: Option[Int] = None,
  facilitatorCount: Int = 0,
  declaredCount: Int = 0,
  activeCount: Int = 0,
  leader: Option[String] = None,
  viewNumber: Int = 0,
  roundElapsedMs: Long = 0,
  phaseElapsedMs: Long = 0,
  stallCount: Int = 0,
  consecutiveAbandonments: Int = 0,
  totalRecoveryAttempts: Int = 0,
  isRunning: Boolean = false,
  missingPeers: List[String] = Nil,
  facilitatorIds: List[String] = Nil,
  // Ready peers reporting a key strictly greater than ours. 0 = cluster-wide stall, not local lag.
  peersAtHigherKey: Int = 0,
  // Reason label of the most recent ROUND_ABANDONED event (e.g. "quorum_infeasible", "max_stalls").
  lastAbandonReason: Option[String] = None,
  // Monotonic millis when sustained quorum-infeasible-without-peers-ahead was first detected. None = no wedge.
  // Cluster.leave() guard reads this; cleared on resetOnSuccessfulRound. See AbandonmentTracker for set/clear logic.
  wedgeDetectedAtMs: Option[Long] = None
)

object ConsensusHealthStatus {

  val empty: ConsensusHealthStatus = ConsensusHealthStatus()

  def ref[F[_]: Async]: F[Ref[F, ConsensusHealthStatus]] =
    Ref.of[F, ConsensusHealthStatus](empty)
}
