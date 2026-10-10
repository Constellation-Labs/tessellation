package io.constellationnetwork.node.shared.infrastructure.consensus.state

import cats.data.StateT
import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.SortedMap

import io.constellationnetwork.node.shared.config.types.ConsensusConfig
import io.constellationnetwork.node.shared.domain.cluster.storage.ClusterStorage
import io.constellationnetwork.node.shared.infrastructure.consensus.{ConsensusResources, PeerDeclarations}
import io.constellationnetwork.schema.peer.{PeerId, Responsive, Unresponsive}
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed

import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

/** Advances consensus state through status phases and extracts final outcome.
  *
  * ==Purpose==
  *
  * Each status transition has specific logic:
  *   - Check if all required declarations received
  *   - Compute majority values
  *   - Create and spread next declaration
  *
  * ==Status Transitions==
  *
  * '''CollectingFacilities → CollectingProposals:'''
  *   - Requirement: All facilitators sent Facility declarations
  *   - Action: Pick majority trigger, create proposal artifact
  *   - Spread: Proposal(artifactInfo, trigger)
  *
  * '''CollectingProposals → CollectingSignatures:'''
  *   - Requirement: All facilitators sent Proposal declarations
  *   - Action: Pick majority artifact hash, sign it
  *   - Spread: MajoritySignature(hash, signature)
  *
  * '''CollectingSignatures → CollectingBinarySignatures:'''
  *   - Requirement: Enough signatures for majority
  *   - Action: Create signed artifact with all signatures
  *   - Spread: BinarySignature(signedArtifact)
  *
  * '''CollectingBinarySignatures → Finished:'''
  *   - Requirement: All facilitators sent BinarySignature
  *   - Action: Build final outcome
  *
  * ==Key Methods==
  *
  * '''advanceStatus(state, resources):''' Try to move to next status
  *
  * '''getConsensusOutcome(state):''' If Finished, extract (prevKey, outcome)
  */

case class Previous[A](a: A)

/** A fully verified same-key outcome ready to enter the ordinary finalization path.
  *
  * `valueHash` lets the shared coordinator fail closed if peers somehow present two different valid certified values. Global L0 owns
  * outcome re-derivation and persistence because those artifact/context effects do not belong in the generic coordinator.
  */
final case class CertifiedOutcomeAdoption[F[_], State](
  valueHash: Hash,
  state: State,
  sideEffect: F[Unit]
)

trait ConsensusStateAdvancer[F[_], Key, Artifact, Context, Status, Outcome, Kind] {

  type State = ConsensusState[Key, Status, Outcome, Kind]
  private type Resources = ConsensusResources[Artifact, Kind]

  def getConsensusOutcome(
    state: ConsensusState[Key, Status, Outcome, Kind]
  ): Option[(Previous[Key], Outcome)]

  /** Whether the chain is still in bootstrap (pre-`bootstrapCompleteProofsThreshold` committee-size history).
    *
    * Used by [[io.constellationnetwork.node.shared.infrastructure.consensus.engine.StallDetector]] to apply an adaptive declaration-timeout
    * multiplier during bootstrap, when fresh-start peers need additional time to respond. Defaults to `false` (post-bootstrap) so
    * implementations that don't track this state behave as before.
    */
  def isBootstrapActive(lastOutcome: Outcome): Boolean = false

  def advanceStatus(resources: ConsensusResources[Artifact, Kind]): StateT[F, ConsensusState[Key, Status, Outcome, Kind], F[Unit]]

  /** Align layer-specific application storage when recovery accepts a newer consensus outcome than the snapshot originally handed to
    * `InitializeFromDownload`.
    *
    * The consensus-outcome endpoint retains only its latest outcome. A fast chain can therefore advance between download convergence and
    * initialization, causing recovery to accept outcome `N+1` while application snapshot storage is still at `N`. Starting consensus from
    * that torn handoff makes the first persisted `N+2` snapshot fail strict contiguity forever. Layers whose recovery path can accept a
    * newer outcome override this hook to install that outcome's artifact and context before consensus starts. Layers without that
    * recovery-storage stack implement an inert hook and preserve their existing download behavior.
    *
    * This is node-local recovery state only. It does not alter artifact bytes, state proofs, committee derivation, or proposal validation.
    */
  def synchronizeDownloadedOutcome(artifact: Signed[Artifact], context: Context): F[Unit]

  /** Verify and re-derive a peer-supplied v35 outcome against this node's locally known parent and frozen round sets. Implementations must
    * return `Left` for every legacy, malformed, or insufficiently proven candidate; callers never trust transport or sidecar bytes
    * directly.
    */
  def certifiedOutcomeAdoption(
    state: ConsensusState[Key, Status, Outcome, Kind],
    candidate: Outcome
  ): F[Either[String, CertifiedOutcomeAdoption[F, ConsensusState[Key, Status, Outcome, Kind]]]]

  /** Layer-local, idempotent maintenance that requires the exact newly committed outcome. It runs after the shared last-outcome CAS and
    * never participates in consensus bytes or state derivation. GL0 uses it for the post-finalization peer-history sidecar; the default is
    * inert.
    */
  def afterConsensusOutcomeCommitted(outcome: Outcome): F[Unit]

  def logger(implicit async: Async[F]): SelfAwareStructuredLogger[F] =
    Slf4jLogger.getLoggerFromName[F](this.getClass.getName)

  protected def clusterStorage: ClusterStorage[F]

  protected def config: ConsensusConfig

  /** v4.1.0 cluster-majority floor gate. When true, the finality quorum is floored at a super/unanimity-majority of
    * `roundStartFacilitators` so a minority Core cannot finalize (see `FinalityQuorum.required`). Defaults to `false` (floor inert) so any
    * advancer that does not opt in is unaffected. The Global L0 advancer overrides it to always true: certified consensus applies the floor
    * in every round, including bootstrap. Must be deterministic across nodes because it feeds the quorum decision.
    */
  protected def clusterFloorActive(state: ConsensusState[Key, Status, Outcome, Kind]): Boolean = false

  /** LIVENESS quorum: Core-sized, never the cluster floor. Consumed by VCC/TC assembly and apply in `StateTransitions`, proposal-embedded
    * certificate validation, and the `StallDetector` feasibility gates. Their effects only take hold through a finalized snapshot, and
    * finalization is floored (see `finalityQuorum`).
    */
  def livenessQuorum(state: ConsensusState[Key, Status, Outcome, Kind]): Int =
    FinalityQuorum.coreQuorum(state.coreFacilitators.value.size, config.quorumThresholdFraction)

  /** FINALITY quorum: carries the cluster-majority floor over the FROZEN round committee outside bootstrap. Used only where a snapshot is
    * committed -- the phase gate `maybeGetAllDeclarations` and the dag-l0 finalization gate.
    */
  def finalityQuorum(state: ConsensusState[Key, Status, Outcome, Kind]): Int =
    FinalityQuorum.required(
      state.coreFacilitators.value.size,
      state.roundStartFacilitators.value.size,
      clusterFloorActive(state),
      config.quorumThresholdFraction
    )

  protected def maybeGetAllDeclarations[A](
    state: State,
    resources: Resources
  )(
    getter: PeerDeclarations => Option[A]
  )(implicit asyncF: Async[F]): F[Option[SortedMap[PeerId, A]]] = {
    // v19 alpha.89: phase-quorum gates on the round committee. Tier 1 peers may declare
    // (Facility / MajoritySignature / BinarySignature) and their declarations are RETURNED in
    // the result so they earn rewards proportionally -- but their absence cannot block the
    // phase from advancing. Pre-alpha.89 this gated on `state.facilitators.value.size` (full
    // committee including Tier 1), which wedged overnight at alpha.88 with "3 active < 4
    // required" when source nodes were signing but community Tier 1 peers stayed silent.
    //
    // v4.1.0 cluster-majority floor: outside bootstrap the GATE set is the FROZEN ROUND COMMITTEE
    // (`roundStartFacilitators`) and the threshold a committee-sized super/unanimity-majority,
    // matching the floored denominator in `FinalityQuorum.required`. This fences the proven
    // 2-of-5 self-finalization fork: a minority Core can no longer satisfy the gate. The COUNTED
    // VOTERS widen with the threshold -- raising the bar while still counting only Core declarations
    // would wedge a healthy mixed committee where Core < committee. During bootstrap
    // (`clusterFloorActive == false`) the gate stays Core-only, byte-identical to cold start.
    //
    // Collection: iterate the active set so Tier 1 declarations land in the result (rewards).
    val activeFacilitators = state.facilitators.value
    val coreSet = state.coreFacilitators.value.toSet
    val floorActive = clusterFloorActive(state)
    val gateSet = if (floorActive) state.roundStartFacilitators.value.toSet else coreSet
    val gateSize = gateSet.size

    // v4.1.0 collection/gate consistency: when the finality floor is active the GATE counts over the FROZEN
    // round committee (`gateSet`). Declarations must therefore be COLLECTED over that SAME frozen universe,
    // not the mutable `state.facilitators` -- a mid-round B1 eviction (proposal acceptance shrinks
    // `state.facilitators`; see the dag-l0 B1 apply) or a withdrawal can drop a frozen-committee member from
    // `state.facilitators` BELOW the floor, losing its declaration from the count and DEADLOCKING a round the
    // frozen committee could otherwise close. We union with the active set defensively (`state.facilitators`
    // is a subset of the frozen committee in practice; admissions do not grow it mid-round). In bootstrap the
    // floor is off and the original active-set collection is preserved byte-identically.
    val collectionUniverse: Set[PeerId] =
      ConsensusStateAdvancer.collectionUniverse(activeFacilitators.toSet, gateSet, floorActive)

    val declarations = collectionUniverse.flatMap { peerId =>
      resources.peerDeclarationsMap
        .get(peerId)
        .flatMap(getter)
        .map((peerId, _))
    }

    val declarationsMap = SortedMap.from(declarations)
    val receivedCount = declarationsMap.size
    val gateReceivedCount = declarationsMap.keys.count(gateSet.contains)

    // Quorum threshold from config. Default: unanimity (1.0 = all must respond).
    // Testnet/mainnet use 0.6666666666666666 (exact 2/3) so community peers don't block rounds.
    // Dev uses 1.0 (unanimity) for clean E2E convergence. Integer arithmetic via
    // `QuorumPolicy.fromFraction` removes the `Double` from consensus math. The threshold here
    // mirrors `finalityQuorum` for the active gate set (Core in bootstrap, committee otherwise).
    val quorumFraction = config.quorumThresholdFraction
    val quorumThreshold = math.max(1, QuorumPolicy.fromFraction(gateSize, quorumFraction))
    val gateDeclared: Set[PeerId] = declarationsMap.keySet.filter(gateSet.contains)

    val required = finalityQuorum(state)

    if (gateDeclared.size >= required)
      logger.debug(
        s"Quorum reached: ${gateReceivedCount}/${gateSize} committee declared (total received ${receivedCount}/${collectionUniverse.size}, need ${quorumThreshold}) for key=${state.key}"
      ) >> declarationsMap.some.pure[F]
    else none[SortedMap[PeerId, A]].pure[F]
  }
}

object ConsensusStateAdvancer {

  /** The phase-gate declaration-collection universe (v4.1.0 cluster-majority floor). When the finality floor is active the gate counts over
    * the FROZEN round committee (`gateSet` = roundStartFacilitators), so the collection universe MUST include `gateSet` -- otherwise a
    * frozen-committee member dropped from the mutable `activeFacilitators` mid-round (a B1 eviction shrinks `state.facilitators` at
    * proposal acceptance; a withdrawal also removes it) loses its declaration from the finality count, and a round the frozen committee
    * could close DEADLOCKS below the floor (the bug Codex flagged in the first v4.1.0 cut). Unioning with `activeFacilitators` is
    * defensive; `state.facilitators` is a subset of the frozen committee in practice (admissions do not grow it mid-round). When the floor
    * is off (bootstrap) the universe is exactly the active set, byte-identical to pre-v4.1.0 collection.
    */
  def collectionUniverse(activeFacilitators: Set[PeerId], gateSet: Set[PeerId], floorActive: Boolean): Set[PeerId] =
    if (floorActive) activeFacilitators ++ gateSet else activeFacilitators
}
