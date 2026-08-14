package io.constellationnetwork.node.shared.infrastructure.consensus.engine

import cats.Applicative
import cats.effect.kernel.{Async, Ref}
import cats.effect.std.Queue
import cats.syntax.all._

import io.constellationnetwork.node.shared.infrastructure.consensus.ConsensusLog.{Category, Event}
import io.constellationnetwork.node.shared.infrastructure.consensus._
import io.constellationnetwork.node.shared.infrastructure.consensus.declaration.{ProposalQC, TimeoutReason}
import io.constellationnetwork.node.shared.infrastructure.consensus.state._
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics

import eu.timepit.refined.auto._
import org.typelevel.log4cats.SelfAwareStructuredLogger

/** Emits (signs, stores locally, gossips) a ViewChangeVote on behalf of this node.
  *
  * Abstraction over the layer-specific (dag-l0 / currency-l0) wiring that needs `KeyPair`, `Gossip`, and `HasherSelector` to produce a
  * properly-signed `Signed[ViewChangeVote]`. The generic engine-level `ViewChangeManager` remains layer-agnostic and dispatches the actual
  * emission through this trait.
  */
trait ViewChangeVoter[F[_], Key] {
  def emitViewChangeVote(
    key: Key,
    fromView: Long,
    toView: Long,
    highestKnownQc: Option[ProposalQC]
  ): F[Unit]
}

object ViewChangeVoter {

  /** No-op voter: used during transition when layer-specific gossip wiring is not yet available. Preserves existing liveness (local view
    * increment path) while higher-layer code can still call `performViewChange` without errors.
    */
  def noop[F[_]: Applicative, Key]: ViewChangeVoter[F, Key] = new ViewChangeVoter[F, Key] {
    def emitViewChangeVote(
      key: Key,
      fromView: Long,
      toView: Long,
      highestKnownQc: Option[ProposalQC]
    ): F[Unit] = Applicative[F].unit
  }
}

/** Manages quorum-certified view-change voting on stall.
  *
  * ==View Change Protocol (Phase 2)==
  *
  * When the leader fails to propose within the timeout (or is detected as unresponsive), this manager signs and broadcasts a
  * `ViewChangeVote` for the `(fromView, toView)` transition. The actual advance of `state.viewNumber` is only performed once a quorum of
  * matching votes assembles into a `ViewChangeCertificate` (see
  * [[io.constellationnetwork.node.shared.infrastructure.consensus.state.StateTransitions.checkViewChangeAssembly]]).
  *
  * This replaces the earlier "local-increment" view-change path (which produced split committees under racing view transitions). Safety is
  * provided by the `VoteLock` gate at local signing time; liveness by the VCC assembly path.
  *
  * Mid-round facilitator eviction is not performed at this layer. Before a legacy Global L0 vote, a VCC may rotate the leader; after a
  * vote, Global L0 deliberately holds the attempt because its artifact-only signature cannot certify a different higher-view outcome
  * envelope. Currency L0 retains its pre-rc.8 view-change policy. V35 replaces the Global L0 hold with verified full-value QCs.
  */
class ViewChangeManager[F[_]: Async: Metrics, Key, Artifact, Ctx, Status, Outcome, Kind](
  storage: ConsensusStorage[F, _, Key, _, _, Status, Outcome, Kind],
  peerQualityTracker: PeerQualityTracker[F],
  queue: Queue[F, ConsensusCommand[Key, Artifact, Ctx, Outcome]],
  logger: SelfAwareStructuredLogger[F],
  voter: ViewChangeVoter[F, Key],
  timeoutVoter: TimeoutVoter[F, Key],
  isOutcomeReady: ConsensusState[Key, Status, Outcome, Kind] => Boolean,
  requestedOrEmitted: Ref[F, Set[ViewChangeManager.RequestId[Key]]]
) {

  /** Release the one-emission latch when a soft reset deliberately discards this key's VCV/TC resources. Ordinary abandonment preserves
    * those resources and therefore must not call this method.
    */
  def resetRequestForKey(key: Key): F[Unit] =
    requestedOrEmitted.update(ViewChangeManager.releaseKey(_, key))

  /** Recovery/download/rollback clears consensus resources across keys, so it also clears the matching local emission latch. */
  def resetAllRequests: F[Unit] = requestedOrEmitted.set(Set.empty)

  /** Request a Phase 2 quorum-certified view change: record peer quality for the old leader, gossip a signed `ViewChangeVote`, and queue
    * `CheckViewChangeAssembly` so the generic state-transitions path can assemble a quorum VCC and deterministically advance the round.
    *
    * This method does not mutate `state.viewNumber` / `state.leader` locally. Only the `checkViewChangeAssembly` path — triggered once
    * enough peers have also emitted their own `ViewChangeVote`s — advances the state. Same-key abandonment remains available only before
    * proposal acceptance/voting; after that boundary Global L0 waits for the existing attempt or peer-ahead recovery.
    *
    * The safety-critical vote lock lives at `storage.tryLockVote` in `buildSignatureTransition`. On the legacy wire an artifact hash does
    * not bind the complete proposal envelope, so after any vote this method suppresses all same-key higher-view VCV/TC emission; v35
    * replaces that conservative hold with a verified QC over the full ProposalValue.
    */
  private[consensus] def performViewChange(
    key: Key,
    observedEpoch: ViewChangeManager.ObservedEpoch,
    timeoutReason: TimeoutReason = TimeoutReason.NoProgress
  ): F[Boolean] =
    (storage.getRoundAttemptId, storage.getResourceGeneration(key), storage.getState(key)).tupled.flatMap {
      case (_, _, None) =>
        Metrics[F].incrementCounter("dag_consensus_view_change_request_stale_total").as(false)

      case (currentAttemptId, currentProgressGeneration, Some(currentState)) =>
        ViewChangeManager
          .requestForObservation(
            key,
            observedEpoch,
            timeoutReason,
            currentState.viewNumber.toLong,
            currentAttemptId,
            currentProgressGeneration,
            isOutcomeReady(currentState)
          )
          .fold(
            ConsensusLog.debug(
              logger,
              Category.Phase,
              key.toString,
              "n/a",
              Event.ViewChange,
              "action" -> "drop_stale_monitor_decision",
              "expectedView" -> observedEpoch.view.toString,
              "currentView" -> currentState.viewNumber.toString,
              "expectedAttemptId" -> observedEpoch.attemptId.toString,
              "currentAttemptId" -> currentAttemptId.toString,
              "expectedProgressGeneration" -> observedEpoch.progressGeneration.toString,
              "currentProgressGeneration" -> currentProgressGeneration.toString,
              "outcomeReady" -> isOutcomeReady(currentState).toString
            ) >> Metrics[F].incrementCounter("dag_consensus_view_change_request_stale_total").as(false)
          ) { command =>
            val requestId = ViewChangeManager.RequestId(key, observedEpoch.view, observedEpoch.attemptId, timeoutReason)
            requestedOrEmitted.modify(ViewChangeManager.registerRequest(_, requestId)).flatMap {
              case false => Metrics[F].incrementCounter("dag_consensus_view_change_request_duplicate_suppressed_total").as(false)
              case true =>
                queue
                  .offer(command)
                  .as(true)
                  .handleErrorWith(error => requestedOrEmitted.update(_ - requestId) >> error.raiseError[F, Boolean])
            }
          }
    }

  /** Command-loop half of `performViewChange`. State transitions, proposal acceptance, and local signing use the same serialized loop, so a
    * vote lock cannot appear between this method's lock check and its VCV/TC emission.
    */
  def emitRequestedViewChange(
    key: Key,
    expectedFromView: Long,
    expectedAttemptId: Long,
    expectedProgressGeneration: Long,
    timeoutReason: TimeoutReason
  ): F[Unit] = {
    val requestId = ViewChangeManager.RequestId(key, expectedFromView, expectedAttemptId, timeoutReason)
    val release = requestedOrEmitted.update(_ - requestId)

    val drain = (storage.getRoundAttemptId, storage.getResourceGeneration(key), storage.getState(key)).tupled.flatMap {
      case (_, _, None) => release >> Metrics[F].incrementCounter("dag_consensus_view_change_request_stale_total")
      case (currentAttemptId, currentProgressGeneration, Some(currentState))
          if !ViewChangeManager.requestStillCurrent(
            expectedFromView,
            expectedAttemptId,
            expectedProgressGeneration,
            currentState.viewNumber.toLong,
            currentAttemptId,
            currentProgressGeneration,
            isOutcomeReady(currentState)
          ) =>
        ConsensusLog.debug(
          logger,
          Category.Phase,
          key.toString,
          "n/a",
          Event.ViewChange,
          "action" -> "drop_stale_request",
          "expectedView" -> expectedFromView.toString,
          "currentView" -> currentState.viewNumber.toString,
          "expectedAttemptId" -> expectedAttemptId.toString,
          "currentAttemptId" -> currentAttemptId.toString,
          "expectedProgressGeneration" -> expectedProgressGeneration.toString,
          "currentProgressGeneration" -> currentProgressGeneration.toString,
          "outcomeReady" -> isOutcomeReady(currentState).toString
        ) >> release >> Metrics[F].incrementCounter("dag_consensus_view_change_request_stale_total")
      case (_, _, Some(currentState)) =>
        emitUnlocked(key, currentState, timeoutReason).handleErrorWith(error => release >> error.raiseError[F, Unit])
    }

    // This also covers failures in the initial storage reads and stale-path
    // logging/metrics, not only failures inside emitUnlocked.
    drain.onError { case _ => release }
  }

  private def emitUnlocked(
    key: Key,
    currentState: ConsensusState[Key, Status, Outcome, Kind],
    timeoutReason: TimeoutReason
  ): F[Unit] = {
    val fromView = currentState.viewNumber.toLong
    val toView = fromView + 1L
    val mode = storage.viewSafetyMode(currentState.certifiedConsensusActive)

    storage.getVoteLock(key).flatMap {
      case maybeLock @ Some(lock) if VoteLock.blocksLegacyViewChange(maybeLock, mode) =>
        // A legacy artifact signature does not bind the proposal envelope. Helping to
        // certify a higher view after voting can only create a view this node is forbidden
        // to sign, and may help a different envelope finalize. Stay on the old attempt and
        // keep collecting its signatures until peer-ahead recovery releases the lock.
        ConsensusLog.warn(
          logger,
          Category.Phase,
          key.toString,
          "n/a",
          Event.ViewChange,
          "oldView" -> fromView.toString,
          "newView" -> toView.toString,
          "oldLeader" -> ConsensusLog.pid(currentState.leader),
          "action" -> "suppressed_legacy_vote_lock",
          "highestVotedView" -> lock.highestVotedView.fold("none")(_.toString),
          "lockedQcView" -> lock.lockedQc.fold("none")(_.view.toString)
        ) >>
          Metrics[F].incrementCounter("dag_consensus_legacy_locked_view_change_suppressed_total")

      case maybeLock =>
        val highestKnownQc = maybeLock.flatMap(_.lockedQc)
        ConsensusLog.info(
          logger,
          Category.Phase,
          key.toString,
          "n/a",
          Event.ViewChange,
          "oldView" -> fromView.toString,
          "newView" -> toView.toString,
          "oldLeader" -> ConsensusLog.pid(currentState.leader),
          "facilitators" -> currentState.facilitators.value.size.toString
        ) >>
          peerQualityTracker.recordViewChange(currentState.leader) >>
          voter.emitViewChangeVote(key, fromView, toView, highestKnownQc) >>
          timeoutVoter.emitTimeoutVote(key, fromView, toView, highestKnownQc, timeoutReason) >>
          // A monitor may already have sampled an AbandonRound while this serialized request was
          // waiting in the queue. Advance the progress epoch only after both local votes have been
          // stored/emitted. That makes such an abandon stale; the assembly commands appended below
          // are then FIFO-ahead of any abandon sampled from the new epoch.
          storage.markPacemakerEmissionProgress(key) >>
          queue.offer(ConsensusCommand.CheckViewChangeAssembly(key)) >>
          queue.offer(ConsensusCommand.CheckTimeoutCertificateAssembly(key))
    }
  }

}

object ViewChangeManager {

  /** Exact monitor snapshot that authorized a pacemaker decision. `performViewChange` must never re-read fresh epochs and attach an old
    * phase/view decision to them; it may only enqueue a request carrying these observed values.
    */
  private[consensus] final case class ObservedEpoch(view: Long, attemptId: Long, progressGeneration: Long)

  private[consensus] final case class RequestId[Key](key: Key, view: Long, attemptId: Long, reason: TimeoutReason)

  /** Register at most one emission per key/view/attempt/reason. A new attempt at the same key prunes the completed attempt's latches, while
    * distinct timeout reasons remain eligible because TimeoutCertificate assembly groups votes by reason.
    */
  private[consensus] def registerRequest[Key](
    existing: Set[RequestId[Key]],
    request: RequestId[Key]
  ): (Set[RequestId[Key]], Boolean) =
    if (existing.contains(request) || existing.exists(id => id.key == request.key && id.attemptId > request.attemptId)) (existing, false)
    else {
      val currentAttemptEntries = existing.filterNot(id => id.key == request.key && id.attemptId < request.attemptId)
      (currentAttemptEntries + request, true)
    }

  /** Construct the serialized command only when the monitor observation still names the exact live view, attempt, and progress epoch.
    * Returning `None` before latch registration prevents a delayed monitor fiber from replacing a newer attempt's coalescing entry.
    */
  private[consensus] def requestForObservation[Key](
    key: Key,
    observed: ObservedEpoch,
    reason: TimeoutReason,
    currentView: Long,
    currentAttemptId: Long,
    currentProgressGeneration: Long,
    outcomeReady: Boolean
  ): Option[ConsensusCommand.RequestViewChange[Key]] =
    Option.when(
      requestStillCurrent(
        observed.view,
        observed.attemptId,
        observed.progressGeneration,
        currentView,
        currentAttemptId,
        currentProgressGeneration,
        outcomeReady
      )
    )(
      ConsensusCommand.RequestViewChange(
        key,
        observed.view,
        observed.attemptId,
        observed.progressGeneration,
        reason
      )
    )

  private[consensus] def releaseKey[Key](existing: Set[RequestId[Key]], key: Key): Set[RequestId[Key]] =
    existing.filterNot(_.key == key)

  private[consensus] def requestStillCurrent(
    expectedView: Long,
    expectedAttemptId: Long,
    expectedProgressGeneration: Long,
    currentView: Long,
    currentAttemptId: Long,
    currentProgressGeneration: Long,
    outcomeReady: Boolean
  ): Boolean =
    !outcomeReady &&
      currentView == expectedView &&
      currentAttemptId == expectedAttemptId &&
      currentProgressGeneration == expectedProgressGeneration
}
