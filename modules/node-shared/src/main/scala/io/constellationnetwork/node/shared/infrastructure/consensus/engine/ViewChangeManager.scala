package io.constellationnetwork.node.shared.infrastructure.consensus.engine

import cats.Eq
import cats.effect.kernel.{Async, Ref}
import cats.syntax.all._

import io.constellationnetwork.node.shared.infrastructure.consensus._
import io.constellationnetwork.node.shared.infrastructure.consensus.state._
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.schema.peer.PeerId

import eu.timepit.refined.auto._

/** Manages deterministic leader re-election and peer eviction when facilitators fail.
  *
  * ==View Change Protocol==
  *
  * When the leader fails to propose within the timeout (or is detected as unresponsive), the view number is incremented and a new leader is
  * selected using rendezvous hashing. All nodes use the same entropy + view number, so they deterministically select the same leader.
  *
  * {{{
  *   1. Increment viewNumber
  *   2. newLeader = selectLeader(facilitators, entropy, newViewNumber)
  *   3. Update state atomically (CAS on viewNumber)
  *   4. Trigger CheckUpdate so new leader's proposal is processed
  * }}}
  *
  * ==Peer Eviction==
  *
  * When facilitators fail to declare within the timeout, `performViewChangeWithEviction` removes them from the active facilitator set. This
  * allows the remaining peers to continue with a reduced quorum instead of being stuck waiting forever for an unresponsive peer.
  *
  * ==Early View Change==
  *
  * If LocalHealthcheck marks the leader as `Unresponsive`, the StallDetector triggers an immediate view change without waiting for the full
  * timeout. This saves ~15s of stall time when the leader is known to be down.
  */
class ViewChangeManager[F[_]: Async: Metrics, Key: Eq, Status, Outcome, Kind](
  storage: ConsensusStorage[F, _, Key, _, _, Status, Outcome, Kind],
  facilitatorSelector: FacilitatorSelector,
  peerQualityTracker: PeerQualityTracker[F],
  queue: cats.effect.std.Queue[F, ConsensusCommand],
  logger: org.typelevel.log4cats.SelfAwareStructuredLogger[F]
) {

  /** Maximum consecutive eviction-skipped view changes before escalating to abandonment.
    * When the same peers keep failing but can't be evicted (below minimum 2), cycling view numbers
    * wastes stall cycles. After this many skipped evictions, signal that the round should be abandoned.
    */
  private val maxSkippedEvictions: Int = 3

  /** Tracks consecutive eviction-skipped view changes for the current round. Reset on successful eviction or round completion. */
  private val skippedEvictionCountRef: Ref[F, Int] = Ref.unsafe(0)

  /** Check if we've exceeded the skipped eviction threshold — callers should abandon the round instead. */
  def shouldEscalateToAbandon: F[Boolean] =
    skippedEvictionCountRef.get.map(_ >= maxSkippedEvictions)

  /** Reset the skipped eviction counter (call on round completion or successful eviction). */
  def resetSkippedEvictions: F[Unit] =
    skippedEvictionCountRef.set(0)

  /** Perform a view change: increment viewNumber, select new leader, update state. */
  def performViewChange(
    key: Key,
    currentState: ConsensusState[Key, Status, Outcome, Kind]
  ): F[Unit] = {
    val newViewNumber = currentState.viewNumber + 1
    val newLeader = facilitatorSelector.selectLeader(
      currentState.facilitators.value,
      currentState.entropy,
      newViewNumber
    )

    ConsensusLog.info(
      logger,
      ConsensusLog.Phase,
      key.toString,
      "n/a",
      "event" -> "VIEW_CHANGE",
      "oldView" -> currentState.viewNumber.toString,
      "newView" -> newViewNumber.toString,
      "oldLeader" -> ConsensusLog.pid(currentState.leader),
      "newLeader" -> ConsensusLog.pid(newLeader),
      "facilitators" -> currentState.facilitators.value.size.toString
    ) >>
      peerQualityTracker.recordViewChange(currentState.leader) >>
      Metrics[F].updateGauge("dag_consensus_view_number", newViewNumber) >>
      storage
        .condModifyState[Unit](key) {
          case Some(state) if state.viewNumber === currentState.viewNumber =>
            val updated: ConsensusState[Key, Status, Outcome, Kind] =
              state.copy(viewNumber = newViewNumber, leader = newLeader)
            (updated.some, ()).some.pure[F]
          case _ =>
            none[(Option[ConsensusState[Key, Status, Outcome, Kind]], Unit)].pure[F]
        }
        .void >>
      queue.offer(ConsensusCommand.CheckUpdate(key))
  }

  /** Perform a view change with peer eviction: remove unresponsive peers, select new leader from remaining.
    *
    * When facilitators fail to declare within the timeout, this method removes them from the active set. The remaining peers can then
    * proceed with a reduced quorum (`ceil(remaining × threshold)`) instead of being stuck waiting for an unresponsive peer.
    *
    * Safety: never evicts below 2 facilitators — falls back to normal view change if eviction would leave fewer than 2.
    *
    * The CAS guard on `viewNumber` prevents conflicting evictions: if the state has already advanced (e.g., because a late declaration
    * arrived and the phase progressed), the eviction is skipped.
    */
  def performViewChangeWithEviction(
    key: Key,
    currentState: ConsensusState[Key, Status, Outcome, Kind],
    peersToEvict: Set[PeerId]
  ): F[Unit] = {
    val remainingFacilitators = currentState.facilitators.value.filterNot(peersToEvict.contains)

    if (remainingFacilitators.size < 2) {
      // Can't evict to below minimum viable cluster — track and fall back to normal view change.
      // After maxSkippedEvictions consecutive skips, callers should escalate to abandonment.
      skippedEvictionCountRef.updateAndGet(_ + 1).flatMap { skipped =>
        ConsensusLog.warn(
          logger,
          ConsensusLog.Phase,
          key.toString,
          "n/a",
          "event" -> "EVICTION_SKIPPED_MIN_FACILITATORS",
          "peersToEvict" -> peersToEvict.size.toString,
          "remaining" -> remainingFacilitators.size.toString,
          "skippedEvictionCount" -> skipped.toString,
          "maxSkippedEvictions" -> maxSkippedEvictions.toString
        ) >> (if (skipped >= maxSkippedEvictions)
                Metrics[F].incrementCounter("dag_consensus_eviction_loop_escalation") >>
                  ConsensusLog.error(
                    logger,
                    ConsensusLog.Phase,
                    key.toString,
                    "n/a",
                    "event" -> "EVICTION_LOOP_ESCALATION",
                    "skippedEvictions" -> skipped.toString,
                    "reason" -> "repeated eviction skips exhausted, signaling abandon"
                  )
              else
                performViewChange(key, currentState))
      }
    } else {
      // Successful eviction — reset the skipped counter
      val newViewNumber = currentState.viewNumber + 1
      val newLeader = facilitatorSelector.selectLeader(remainingFacilitators, currentState.entropy, newViewNumber)

      skippedEvictionCountRef.set(0) >>
      ConsensusLog.warn(
        logger,
        ConsensusLog.Phase,
        key.toString,
        "n/a",
        "event" -> "VIEW_CHANGE_WITH_EVICTION",
        "evicted" -> peersToEvict.size.toString,
        "remaining" -> remainingFacilitators.size.toString,
        "oldView" -> currentState.viewNumber.toString,
        "newView" -> newViewNumber.toString,
        "oldLeader" -> ConsensusLog.pid(currentState.leader),
        "newLeader" -> ConsensusLog.pid(newLeader),
        "evictedPeers" -> peersToEvict.toList.map(ConsensusLog.pid).mkString(",")
      ) >>
        peersToEvict.toList.traverse_(peerQualityTracker.recordViewChange) >>
        Metrics[F].updateGauge("dag_consensus_view_number", newViewNumber) >>
        Metrics[F].incrementCounter("dag_consensus_peer_eviction") >>
        storage
          .condModifyState[Unit](key) {
            case Some(state) if state.viewNumber === currentState.viewNumber =>
              val updated: ConsensusState[Key, Status, Outcome, Kind] =
                state.copy(
                  facilitators = Facilitators(remainingFacilitators),
                  removedFacilitators = RemovedFacilitators(state.removedFacilitators.value ++ peersToEvict),
                  viewNumber = newViewNumber,
                  leader = newLeader
                )
              (updated.some, ()).some.pure[F]
            case _ =>
              none[(Option[ConsensusState[Key, Status, Outcome, Kind]], Unit)].pure[F]
          }
          .void >>
        queue.offer(ConsensusCommand.CheckUpdate(key))
    }
  }
}
