package io.constellationnetwork.node.shared.infrastructure.consensus.engine

import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.ConsensusTrigger
import io.constellationnetwork.schema.gossip.{CommonRumor, PeerRumor}
import io.constellationnetwork.schema.peer.{Peer, PeerId}
import io.constellationnetwork.security.signature.Signed

/** Commands that drive the Consensus Finite State Machine (FSM).
  *
  * The consensus engine is event-driven: all state changes happen in response to commands placed on a queue. This decouples the sources of
  * events (gossip, timers, API calls) from the processing logic, making the system easier to reason about and test.
  *
  * ==Type parameters==
  *
  * The trait is parameterized by the four engine types that previously leaked through as `Any` and were recovered via unsafe `asInstanceOf`
  * inside the FSM dispatch:
  *   - `Key` — consensus round key (e.g. `GlobalSnapshotKey`)
  *   - `Artifact` — produced artifact (e.g. `GlobalIncrementalSnapshot`); only `Signed[Artifact]` is carried
  *   - `Ctx` — consensus context (e.g. `GlobalSnapshotContext`)
  *   - `Outcome` — final round outcome (carries key, artifact, context, trigger via lenses)
  *
  * Variance is `+` on every parameter so the no-payload commands (`TimeTick`, `WithdrawFromConsensus`, etc.) can declare
  * `ConsensusCommand[Nothing, Nothing, Nothing, Nothing]` and remain assignable into any specialized queue.
  *
  * ==Command Categories==
  *
  * '''Round Control:'''
  *   - `StartRound` - Begin a new consensus round with optional trigger
  *   - `TimeTick` - Time-based trigger fired (start round with TimeTrigger)
  *   - `FacilitateByEvent` - Event-based trigger (start round with EventTrigger)
  *   - `RoundCompleted` - Round ended without producing outcome
  *   - `ConsensusFinished` - Round completed successfully with outcome
  *
  * '''Rumor Processing:'''
  *   - `RumorReceived` - Gossip layer delivered a peer or common rumor
  *   - `CheckUpdate` - Re-evaluate state after new data arrived
  *
  * '''Lifecycle:'''
  *   - `InitializeFromDownload` - Node joining cluster, initialize from peers
  *   - `InitializeFromRollback` - Node restarting after rollback
  *   - `WithdrawFromConsensus` - Node leaving consensus participation
  *   - `PeerObserved` - New peer entered Observing state
  *
  * @see
  *   ConsensusFSM for command routing logic
  */
sealed trait ConsensusCommand[+Key, +Artifact, +Ctx, +Outcome]

object ConsensusCommand {
  final case class RumorReceived(rumor: Either[PeerRumor[_], CommonRumor[_]]) extends ConsensusCommand[Nothing, Nothing, Nothing, Nothing]
  final case class StartRound(trigger: Option[ConsensusTrigger]) extends ConsensusCommand[Nothing, Nothing, Nothing, Nothing]
  case object TimeTick extends ConsensusCommand[Nothing, Nothing, Nothing, Nothing]
  case object FacilitateByEvent extends ConsensusCommand[Nothing, Nothing, Nothing, Nothing]

  final case class CheckUpdate[Key](key: Key) extends ConsensusCommand[Key, Nothing, Nothing, Nothing]
  final case class CheckViewChangeAssembly[Key](key: Key) extends ConsensusCommand[Key, Nothing, Nothing, Nothing]
  final case class CheckViewChangeApply[Key](key: Key, fromView: Long, toView: Long)
      extends ConsensusCommand[Key, Nothing, Nothing, Nothing]
  final case class CheckTimeoutCertificateAssembly[Key](key: Key) extends ConsensusCommand[Key, Nothing, Nothing, Nothing]
  final case class CheckTimeoutCertificateApply[Key](key: Key, fromView: Long, toView: Long)
      extends ConsensusCommand[Key, Nothing, Nothing, Nothing]
  // EvictionVote assembly is per-target: different targets accumulate quorums independently,
  // so this command carries both the round key and the target peer whose votes should be
  // checked. Dispatched from the event loop to StateTransitions.checkEvictionAssembly.
  final case class CheckEvictionAssembly[Key](key: Key, target: PeerId) extends ConsensusCommand[Key, Nothing, Nothing, Nothing]
  // B2 admission assembly — symmetric to CheckEvictionAssembly. Dispatched when a new
  // AdmissionVote has been locally stored and the state transition should attempt
  // certificate assembly for `target` at round `key`.
  final case class CheckAdmissionAssembly[Key](key: Key, target: PeerId) extends ConsensusCommand[Key, Nothing, Nothing, Nothing]

  /** Round ended without producing an outcome. `expectedAttemptId = Some(n)` causes the FSM to drop the command if the round has advanced
    * past attempt `n` (state mutation bumped `ConsensusStorage.roundAttemptId`). `None` means unconditional — reserved for force-recovery
    * paths where the round must always complete.
    */
  final case class RoundCompleted(expectedAttemptId: Option[Long] = None) extends ConsensusCommand[Nothing, Nothing, Nothing, Nothing]

  /** Request to abandon the round at `key` for `reason`. Enqueued by the `StallDetector` monitor fiber instead of calling
    * `AbandonmentTracker.abandonRound` directly. `abandonRound` mutates per-key state via `condModifyState`; running it on the monitor
    * fiber raced the command loop's own `condModifyState` calls (non-atomic get -> effect -> set lost-update). Routing through the queue
    * serializes every `condModifyState` writer onto the single command-loop fiber -- the invariant documented at
    * `ConsensusStorage.condModifyState`. The handler re-checks at drain time that the round has not produced an outcome (see
    * `abandonRound`), so a round that completed between the monitor's decision and this command draining is never wiped.
    */
  final case class AbandonRound[Key](key: Key, reason: AbandonReason) extends ConsensusCommand[Key, Nothing, Nothing, Nothing]
  final case class InternalScheduled[Key, Artifact, Ctx, Outcome](inner: ConsensusCommand[Key, Artifact, Ctx, Outcome])
      extends ConsensusCommand[Key, Artifact, Ctx, Outcome]
  final case class PeerObserved(peer: Peer) extends ConsensusCommand[Nothing, Nothing, Nothing, Nothing]
  final case class InitializeFromDownload[Key, Artifact, Ctx](
    key: Key,
    artifact: Signed[Artifact],
    context: Ctx,
    isRecovery: Boolean = false
  ) extends ConsensusCommand[Key, Artifact, Ctx, Nothing]
  final case class InitializeFromRollback[Key, Outcome](key: Key, outcome: Outcome, deferFirstRound: Boolean = false)
      extends ConsensusCommand[Key, Nothing, Nothing, Outcome]
  case object WithdrawFromConsensus extends ConsensusCommand[Nothing, Nothing, Nothing, Nothing]
  final case class ConsensusFinished[Key, Outcome](key: Key, outcome: Outcome, trigger: ConsensusTrigger)
      extends ConsensusCommand[Key, Nothing, Nothing, Outcome]
}
