package io.constellationnetwork.node.shared.infrastructure.consensus.engine

import cats.Applicative
import cats.effect.kernel.Async
import cats.effect.std.Queue
import cats.syntax.all._

import io.constellationnetwork.node.shared.infrastructure.consensus.ConsensusLog.{Category, Event}
import io.constellationnetwork.node.shared.infrastructure.consensus._
import io.constellationnetwork.node.shared.infrastructure.consensus.declaration.{ProposalQC, TimeoutReason}
import io.constellationnetwork.node.shared.infrastructure.consensus.state._

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
  * Mid-round facilitator eviction is not performed at this layer: if a facilitator is genuinely unreachable, the stall-cycle abandonment
  * path in [[StallDetector]] handles it (the round is abandoned and retried with the current eligibility set).
  */
class ViewChangeManager[F[_]: Async, Key, Artifact, Ctx, Status, Outcome, Kind](
  storage: ConsensusStorage[F, _, Key, _, _, Status, Outcome, Kind],
  peerQualityTracker: PeerQualityTracker[F],
  queue: Queue[F, ConsensusCommand[Key, Artifact, Ctx, Outcome]],
  logger: SelfAwareStructuredLogger[F],
  voter: ViewChangeVoter[F, Key],
  timeoutVoter: TimeoutVoter[F, Key]
) {

  /** Request a Phase 2 quorum-certified view change: record peer quality for the old leader, gossip a signed `ViewChangeVote`, and queue
    * `CheckViewChangeAssembly` so the generic state-transitions path can assemble a quorum VCC and deterministically advance the round.
    *
    * This method does not mutate `state.viewNumber` / `state.leader` locally. Only the `checkViewChangeAssembly` path — triggered once
    * enough peers have also emitted their own `ViewChangeVote`s — advances the state. If the VCC quorum never assembles (e.g. too few
    * responsive peers), the existing stall-cycle abandonment path takes over.
    *
    * The safety-critical double-sign prevention lives at `storage.tryLockVote` in `buildSignatureTransition` and is unaffected by how view
    * transitions are driven: even under racing local view-change attempts, neither path can sign two different hashes at the same `(key,
    * view)` pair.
    */
  def performViewChange(
    key: Key,
    currentState: ConsensusState[Key, Status, Outcome, Kind],
    timeoutReason: TimeoutReason = TimeoutReason.NoProgress
  ): F[Unit] = {
    val fromView = currentState.viewNumber.toLong
    val toView = fromView + 1L

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
      storage.getVoteLock(key).flatMap { maybeLock =>
        val highestKnownQc = maybeLock.flatMap(_.lockedQc)
        voter.emitViewChangeVote(key, fromView, toView, highestKnownQc) >>
          timeoutVoter.emitTimeoutVote(key, fromView, toView, highestKnownQc, timeoutReason)
      } >>
      queue.offer(ConsensusCommand.CheckViewChangeAssembly(key)) >>
      queue.offer(ConsensusCommand.CheckTimeoutCertificateAssembly(key))
  }

}
