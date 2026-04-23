package io.constellationnetwork.node.shared.infrastructure.consensus.engine

import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.ConsensusTrigger
import io.constellationnetwork.schema.gossip.{CommonRumor, PeerRumor}
import io.constellationnetwork.schema.peer.Peer

/** Commands that drive the Consensus Finite State Machine (FSM).
  *
  * The consensus engine is event-driven: all state changes happen in response to commands placed on a queue. This decouples the sources of
  * events (gossip, timers, API calls) from the processing logic, making the system easier to reason about and test.
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

sealed trait ConsensusCommand

object ConsensusCommand {
  case class RumorReceived(rumor: Either[PeerRumor[_], CommonRumor[_]]) extends ConsensusCommand
  case class StartRound(trigger: Option[ConsensusTrigger]) extends ConsensusCommand
  case object TimeTick extends ConsensusCommand
  case object FacilitateByEvent extends ConsensusCommand
  case class CheckUpdate(key: Any) extends ConsensusCommand
  case class CheckViewChangeAssembly(key: Any) extends ConsensusCommand
  // EvictionVote assembly is per-target: different targets accumulate quorums independently,
  // so this command carries both the round key and the target peer whose votes should be
  // checked. Dispatched from the event loop to StateTransitions.checkEvictionAssembly.
  case class CheckEvictionAssembly(key: Any, target: Any) extends ConsensusCommand
  // B2 admission assembly — symmetric to CheckEvictionAssembly. Dispatched when a new
  // AdmissionVote has been locally stored and the state transition should attempt
  // certificate assembly for `target` at round `key`.
  case class CheckAdmissionAssembly(key: Any, target: Any) extends ConsensusCommand

  /** Round ended without producing an outcome. `expectedAttemptId = Some(n)` causes the FSM to drop the command if the round has advanced
    * past attempt `n` (state mutation bumped `ConsensusStorage.roundAttemptId`). `None` means unconditional — reserved for force-recovery
    * paths where the round must always complete.
    */
  case class RoundCompleted(expectedAttemptId: Option[Long] = None) extends ConsensusCommand
  case class InternalScheduled(inner: ConsensusCommand) extends ConsensusCommand
  case class PeerObserved(peer: Peer) extends ConsensusCommand
  case class InitializeFromDownload(key: Any, artifact: Any, context: Any, isRecovery: Boolean = false) extends ConsensusCommand
  case class InitializeFromRollback(key: Any, outcome: Any) extends ConsensusCommand
  case object WithdrawFromConsensus extends ConsensusCommand
  case class IgnoreUnexpectedRumor(rumor: Any) extends ConsensusCommand
  case class ConsensusFinished(key: Any, outcome: Any, trigger: ConsensusTrigger) extends ConsensusCommand
}
