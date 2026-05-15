package io.constellationnetwork.node.shared.infrastructure.consensus.state

import cats.Show
import cats.syntax.show._

import scala.concurrent.duration.FiniteDuration

import io.constellationnetwork.node.shared.infrastructure.consensus.PeerDeclarations
import io.constellationnetwork.node.shared.infrastructure.consensus.declaration.PeerDeclaration
import io.constellationnetwork.node.shared.infrastructure.selfhealth.SelfHealthHint
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import monocle.Lens
import monocle.macros.GenLens

/** Immutable snapshot of a consensus round's current state.
  *
  * ==Structure==
  *
  * {{{
  *   ConsensusState(
  *     key: Key,                          // Ordinal being decided (e.g., SnapshotOrdinal(42))
  *     status: Status,                    // Current phase (CollectingFacilities, etc.)
  *     facilitators: Facilitators,        // Active participants
  *     withdrawnFacilitators: Set[PeerId], // Peers who left
  *     removedFacilitators: Set[PeerId],  // Peers kicked out
  *     leader: PeerId,                    // Current round leader
  *     viewNumber: Int,                   // View change counter
  *     entropy: Hash,                     // Entropy for leader selection
  *     createdAt: FiniteDuration          // For metrics
  *   )
  * }}}
  *
  * ==Status Progression==
  *
  * {{{
  *   CollectingFacilities    // Waiting for all peers' facility info
  *         │
  *         ▼
  *   CollectingProposals     // Waiting for leader's artifact proposal
  *         │
  *         ▼
  *   CollectingSignatures    // Waiting for majority signatures
  *         │
  *         ▼
  *   CollectingBinarySignatures  // Waiting for final binary signatures (currency only)
  *         │
  *         ▼
  *   Finished                // Outcome ready, round complete
  * }}}
  *
  * ==View Change==
  *
  * When the leader fails to propose within the timeout, the view number is incremented and a new leader is selected using rendezvous
  * hashing with the round's entropy. This avoids the complexity of the previous lock/unlock/ACK voting mechanism.
  */

@derive(eqv, encoder, decoder)
case class Facilitators(value: List[PeerId])

@derive(eqv, encoder, decoder)
case class EligibleFacilitators(value: List[PeerId])
object EligibleFacilitators {
  def empty: EligibleFacilitators = EligibleFacilitators(List.empty)
}

@derive(eqv, encoder, decoder)
case class RemovedFacilitators(value: Set[PeerId])
object RemovedFacilitators {
  def empty: RemovedFacilitators = RemovedFacilitators(Set.empty)
}

@derive(eqv, encoder, decoder)
case class WithdrawnFacilitators(value: Set[PeerId])
object WithdrawnFacilitators {
  def empty: WithdrawnFacilitators = WithdrawnFacilitators(Set.empty)
}

/** B2 admissions applied this round via the leader's accepted `AdmissionCertificate`s. Populated at `buildSignatureTransition` time after
  * ACS validation; consumed at outcome-extraction time to clear these peer IDs from the carried-forward `readmissionCountdown`.
  *
  * Parallels [[RemovedFacilitators]] (B1) — same flow, opposite direction: B1 removes from committee, B2 removes from probation.
  */
@derive(eqv, encoder, decoder)
case class AdmittedFacilitators(value: Set[PeerId])
object AdmittedFacilitators {
  def empty: AdmittedFacilitators = AdmittedFacilitators(Set.empty)
}

/** v7 (flaky-byzantine): leader's positive observation of which round-start facilitators sent a Facility declaration during this round's
  * facility-collection window. Carried on the round's accepted Proposal and persisted here for the duration of the round so that the
  * outcome-extraction step can credit `peerQuality.completed` only for peers who actually participated, not for any non-fork-evicted
  * facilitator (the v3-codex-flagged "silent peers score (1,1)" blindness).
  *
  * '''REPLACE semantics on accept''', NOT union (codex turn 2 review 2026-04-28). The field is canonically the latest accepted proposal's
  * observedResponders; view-N accepting a new proposal must REPLACE state.observedResponders, never `++`. Otherwise an honest view change
  * would over-credit late peers from view-K-1.
  *
  * Determinism source: leader's signed rumor envelope (RumorValidator.scala:50 enforces signers.contains(rumor.origin)) cryptographically
  * binds the leader to their stated set under the trusted-allowlist + flaky-byzantine threat model.
  */
@derive(eqv, encoder, decoder)
case class ObservedResponders(value: Set[PeerId])
object ObservedResponders {
  def empty: ObservedResponders = ObservedResponders(Set.empty)
}

/** v15 (2026-05-15) self-health throttle: leader's canonical view of each observed responder's `SelfHealthHint`, copied into local state
  * when a Proposal is accepted. Carried forward into the next round's Outcome (`peerSelfHealth`) which then feeds `selectLeaderWeighted` to
  * demote Degraded peers to tier 1 and Critical peers to tier 2.
  *
  * REPLACE semantics on accept (mirrors `ObservedResponders` REPLACE rationale): an honest view change adopts the new proposal's map; old
  * view's hints are discarded so a stale Healthy claim cannot bleed into view-N+1 selection.
  */
@derive(eqv, encoder, decoder)
case class ObservedSelfHealth(value: Map[PeerId, SelfHealthHint])
object ObservedSelfHealth {
  def empty: ObservedSelfHealth = ObservedSelfHealth(Map.empty)
}

@derive(eqv, encoder, decoder, show)
case class Candidates(value: Set[PeerId])
object Candidates {
  def empty: Candidates = Candidates(Set.empty)
}

@derive(eqv)
case class ConsensusState[Key, Status, Outcome, Kind](
  key: Key,
  lastOutcome: Outcome,
  facilitators: Facilitators,
  // Canonical committee frozen at round creation. Unlike `facilitators` (which
  // `ConsensusStateUpdater.updateFacilitators` mutates when peers withdraw
  // mid-round based on local `withdrawalsMap`), this field is set once by the
  // state creator from the deterministic committee selection and never changes
  // for the lifetime of the round.
  //
  // Why: nodes observe `DECL_WITHDRAWN kind=Signature` at different phases,
  // producing divergent `state.facilitators` at round finish. If the outcome's
  // `facilitators` / `completedFacilitators` / `facilitatorsHash` are derived
  // from the mutable set, nodes write divergent `lastOutcome` → divergent
  // next-round committees → fork. Deriving those from `roundStartFacilitators`
  // restores cross-node determinism. Observed 2026-04-23 at ord 4→5 where
  // gl0-4's withdrawal was captured by half the cluster pre-finish and half
  // post-finish; see `.workspace/codex-response-ord5-facilitator-fork-apr23.md`.
  //
  // Read-sites that MUST use this field: outcome.facilitators construction,
  // completedFacilitators derivation, Finished.facilitatorsHash, VCV/eviction
  // vote facilitatorsHash. Read-sites that MUST keep `facilitators`: in-round
  // liveness (StallDetector, gossip spread targets, quorum-threshold calc,
  // active-committee validation).
  roundStartFacilitators: Facilitators,
  status: Status,
  createdAt: FiniteDuration,
  removedFacilitators: RemovedFacilitators = RemovedFacilitators.empty,
  withdrawnFacilitators: WithdrawnFacilitators = WithdrawnFacilitators.empty,
  eligibleFacilitators: EligibleFacilitators = EligibleFacilitators.empty,
  admittedFacilitators: AdmittedFacilitators = AdmittedFacilitators.empty,
  observedResponders: ObservedResponders = ObservedResponders.empty,
  observedSelfHealth: ObservedSelfHealth = ObservedSelfHealth.empty,
  leader: PeerId,
  viewNumber: Int = 0,
  entropy: Hash
)

object ConsensusState {
  implicit def showInstance[K: Show, S: Show, O, Kind: Show]: Show[ConsensusState[K, S, O, Kind]] = { cs =>
    s"""ConsensusState{
       |key=${cs.key.show},
       |leader=${cs.leader.show},
       |viewNumber=${cs.viewNumber.show},
       |facilitatorCount=${cs.facilitators.value.size.show},
       |removedFacilitators=${cs.removedFacilitators.value.show},
       |withdrawnFacilitators=${cs.withdrawnFacilitators.value.show},
       |status=${cs.status.show}
       |}""".stripMargin.replace(",\n", ", ")
  }

  implicit def _facilitators[K, S, O, Kind]: Lens[ConsensusState[K, S, O, Kind], Facilitators] =
    GenLens[ConsensusState[K, S, O, Kind]](_.facilitators)

  implicit def _removedFacilitators[K, S, O, Kind]: Lens[ConsensusState[K, S, O, Kind], RemovedFacilitators] =
    GenLens[ConsensusState[K, S, O, Kind]](_.removedFacilitators)
}

trait ConsensusOps[S, Kind] {
  def collectedKinds(status: S): Set[Kind]
  def maybeCollectingKind(status: S): Option[Kind]
  def kindGetter: Kind => PeerDeclarations => Option[PeerDeclaration]
  def isFinished(status: S): Boolean
  def isProposalPhase(status: S): Boolean

  /** True while the round is collecting MajoritySignature declarations. Consumed by StallDetector to pump periodic CheckUpdate commands —
    * the signature-grace path in the advancer returns `none[Transition]` when quorum is met but the committee isn't full yet, and that
    * decision only re-evaluates on subsequent `checkUpdate` invocations. Without a heartbeat in this phase, a round that met quorum and
    * received no further peer signatures would wedge until an unrelated resource event fired (observed 2026-04-24 E2E, 14.7s wedge).
    */
  def isSignaturesPhase(status: S): Boolean

  /** Phase index for adaptive timeout multipliers. 0 = CollectingFacilities, 1 = CollectingProposals, 2 = CollectingSignatures, 3 =
    * CollectingBinarySignatures (currency only), higher = Finished.
    */
  def phaseIndex(status: S): Int

  /** Produce a fresh CollectingFacilities-shape status suitable for re-entering phase 0 after a Phase 2 view change.
    *
    * The facilitatorsHash and lastSnapshotHash carried on the current status are preserved (facilitator set is fixed across views in Phase
    * 2; the snapshot hash reflects the committed tail). The trigger is cleared so the new view re-collects fresh Facility declarations.
    */
  def freshCollectingFacilities(status: S): Option[S]
}

@derive(eqv)
case class ArtifactInfo[A, C](artifact: A, context: C, hash: Hash)
object ArtifactInfo {
  implicit def showInstance[A, C]: Show[ArtifactInfo[A, C]] = pi => s"ArtifactInfo{hash=${pi.hash.show}}"
}
