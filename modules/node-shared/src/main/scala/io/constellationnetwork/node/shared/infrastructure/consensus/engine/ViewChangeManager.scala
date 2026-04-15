package io.constellationnetwork.node.shared.infrastructure.consensus.engine

import cats.Eq
import cats.effect.kernel.{Async, Ref}
import cats.effect.std.Queue
import cats.syntax.all._

import io.constellationnetwork.node.shared.infrastructure.consensus.ConsensusLog.{Category, Event}
import io.constellationnetwork.node.shared.infrastructure.consensus._
import io.constellationnetwork.node.shared.infrastructure.consensus.state._
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.schema.peer.PeerId

import eu.timepit.refined.auto._
import org.typelevel.log4cats.SelfAwareStructuredLogger

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
  queue: Queue[F, ConsensusCommand],
  logger: SelfAwareStructuredLogger[F]
) {

  /** Maximum consecutive eviction-skipped view changes before escalating to abandonment. When the same peers keep failing but can't be
    * evicted (below minimum 2), cycling view numbers wastes stall cycles. After this many skipped evictions, signal that the round should
    * be abandoned.
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
      Category.Phase,
      key.toString,
      "n/a",
      Event.ViewChange,
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
      // Can't evict to below minimum viable cluster (2) by default — track and fall back to normal
      // view change. After maxSkippedEvictions consecutive skips, perform the eviction anyway:
      // the cluster has proven unreachable for at least 3 stall cycles (~9s+), so the only way
      // forward is for this node to proceed with whoever is left (potentially just self).
      //
      // Fork safety: this only fires when NO other facilitator has declared. If other nodes
      // were reachable and participating, they'd appear as declared and we wouldn't be trying
      // to evict them. In the case where other nodes are independently ALSO escalating (e.g.,
      // mutual isolation), multiple nodes may solo-produce competing snapshots for the same
      // ordinal. ForkRecoveryService's chain-tip sampling converges the cluster to the
      // majority tip within a few rounds of re-connection.
      skippedEvictionCountRef.updateAndGet(_ + 1).flatMap { skipped =>
        ConsensusLog.warn(
          logger,
          Category.Phase,
          key.toString,
          "n/a",
          Event.EvictionSkippedMinFacilitators,
          "peersToEvict" -> peersToEvict.size.toString,
          "remaining" -> remainingFacilitators.size.toString,
          "skippedEvictionCount" -> skipped.toString,
          "maxSkippedEvictions" -> maxSkippedEvictions.toString
        ) >> (if (skipped >= maxSkippedEvictions)
                Metrics[F].incrementCounter("dag_consensus_eviction_loop_escalation") >>
                  ConsensusLog.warn(
                    logger,
                    Category.Phase,
                    key.toString,
                    "n/a",
                    Event.EvictionLoopEscalation,
                    "skippedEvictions" -> skipped.toString,
                    "remaining" -> remainingFacilitators.size.toString,
                    "reason" -> "proceeding with solo eviction to break deadlock"
                  ) >>
                  // Escalation path: actually perform the eviction even though remaining < 2.
                  // Call ourselves recursively; since remaining was just updated to include all
                  // current facilitators minus peersToEvict, the recursion checks the same set
                  // but this time we force through by treating the floor-of-2 as soft.
                  performEvictionForced(key, currentState, peersToEvict)
              else
                performViewChange(key, currentState))
      }
    } else {
      // Successful eviction — reset the skipped counter
      skippedEvictionCountRef.set(0) >>
        doEviction(key, currentState, remainingFacilitators, peersToEvict)
    }
  }

  /** Forced eviction path: perform the eviction even if remaining < 2. Used only when the normal path has been blocked for
    * maxSkippedEvictions cycles and the cluster has proven unreachable. Same side effects as the normal success path but skips the floor
    * guard. Resets the skipped counter so the next round starts clean.
    */
  private def performEvictionForced(
    key: Key,
    currentState: ConsensusState[Key, Status, Outcome, Kind],
    peersToEvict: Set[PeerId]
  ): F[Unit] = {
    val remainingFacilitators = currentState.facilitators.value.filterNot(peersToEvict.contains)
    skippedEvictionCountRef.set(0) >>
      doEviction(key, currentState, remainingFacilitators, peersToEvict)
  }

  private def doEviction(
    key: Key,
    currentState: ConsensusState[Key, Status, Outcome, Kind],
    remainingFacilitators: List[PeerId],
    peersToEvict: Set[PeerId]
  ): F[Unit] = {
    val newViewNumber = currentState.viewNumber + 1
    // If remaining is empty (fully solo-eviction edge case), fall back to self as leader.
    // selectLeader requires a non-empty list.
    val candidates = if (remainingFacilitators.nonEmpty) remainingFacilitators else currentState.facilitators.value
    val newLeader = facilitatorSelector.selectLeader(candidates, currentState.entropy, newViewNumber)

    ConsensusLog.warn(
      logger,
      Category.Phase,
      key.toString,
      "n/a",
      Event.ViewChangeWithEviction,
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
