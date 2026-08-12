package io.constellationnetwork.node.shared.infrastructure.consensus.state

import cats.data.StateT
import cats.effect.{Async, Clock}
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}
import scala.concurrent.duration.FiniteDuration

import io.constellationnetwork.node.shared.config.types.ConsensusConfig
import io.constellationnetwork.node.shared.domain.cluster.storage.ClusterStorage
import io.constellationnetwork.node.shared.infrastructure.consensus.{ConsensusResources, PeerDeclarations}
import io.constellationnetwork.schema.peer.{PeerId, Responsive, Unresponsive}
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

  def logger(implicit async: Async[F]): SelfAwareStructuredLogger[F] =
    Slf4jLogger.getLoggerFromName[F](this.getClass.getName)

  protected def clusterStorage: ClusterStorage[F]

  protected def config: ConsensusConfig

  /** Layer-specific extraction of the most recent `controllerEvidence` entry's `completedSigners` from the carried outcome -- the
    * deterministic voter anchor for `QuorumDenominatorShrink`. Consensus-agreed signed-outcome data; see the rung's scaladoc for the full
    * two-node determinism argument. Defaults to `None` (rung inert) for implementations that do not carry evidence.
    */
  protected def latestEvidenceSigners(lastOutcome: Outcome): Option[SortedSet[PeerId]] = None

  /** Layer-specific extraction of the parent outcome's `consensusEndTime` (last `recentRoundEndTimes` entry) -- the shared time anchor for
    * `QuorumDenominatorShrink` escalation. Defaults to `None` (rung inert).
    */
  protected def lastOutcomeEndTimeMs(lastOutcome: Outcome): Option[Long] = None

  /** v4.1.0 cluster-majority floor gate. When true (the production default for L0 snapshot consensus, OFF during bootstrap), the finality
    * quorum is floored at a super/unanimity-majority of `roundStartFacilitators` so a minority Core cannot finalize (see
    * `QuorumDenominatorShrink.decide`). Defaults to `false` (floor inert, byte-identical to pre-floor behavior) so any advancer that does
    * not opt in is unaffected; the gl0 and currency-l0 advancers override it to `!isInBootstrap(state)`. Must be deterministic across nodes
    * (it feeds the quorum decision): both overrides derive it from `state.lastOutcome.recentProofSizes`, which is signed consensus data.
    */
  protected def clusterFloorActive(state: ConsensusState[Key, Status, Outcome, Kind]): Boolean = false

  /** Shared derivation for both quorum decisions (see `quorumShrinkDecision` and `quorumFinalityDecision`). The ONLY difference between the
    * two is `applyClusterFloor`; every other input is identical so the rung anchors cannot drift between them. Pure except for the
    * wall-clock read.
    */
  private def decideQuorum(
    state: ConsensusState[Key, Status, Outcome, Kind],
    applyClusterFloor: Boolean
  )(implicit asyncF: Async[F]): F[QuorumDenominatorShrink.Decision] =
    Clock[F].realTime.map { now =>
      QuorumDenominatorShrink.decide(
        coreSize = state.coreFacilitators.value.size,
        applyClusterFloor = applyClusterFloor,
        quorumThresholdFraction = config.quorumThresholdFraction,
        latestEvidenceSigners = latestEvidenceSigners(state.lastOutcome),
        roundStartFacilitators = state.roundStartFacilitators.value.toSet,
        parentEndTimeMs = lastOutcomeEndTimeMs(state.lastOutcome),
        nowMs = now.toMillis,
        viewIntervalMs = config.viewInterval.toMillis,
        activationViews = config.quorumShrinkActivationViews
      )
    }

  /** LIVENESS-cert decision: Core-sized quorum, NEVER the v4.1.0 cluster floor. Consumed by the VCC/TC assembly+apply sites in
    * `StateTransitions`, the proposal-embedded cert validation in both layer advancers, and the `StallDetector` feasibility gates. These
    * mechanisms (rotate a wedged leader, evict/admit to reconfigure the committee) MUST keep working in a degraded committee or a single
    * dead leader wedges the round; flooring them would re-create the very leader-rotation/reconfiguration deadlock the shrink rung was
    * built to break. They are safe to leave Core-sized because their EFFECTS only take hold via a finalized snapshot, and finalization IS
    * floored (see `quorumFinalityDecision`). Byte-identical to pre-v4.1.0 behavior. With `config.quorumShrinkActivationViews <= 0` (the
    * default) or absent anchors the returned decision is inert.
    */
  def quorumShrinkDecision(
    state: ConsensusState[Key, Status, Outcome, Kind]
  )(implicit asyncF: Async[F]): F[QuorumDenominatorShrink.Decision] =
    decideQuorum(state, applyClusterFloor = false)

  /** FINALITY decision: carries the v4.1.0 cluster-majority floor (a super/unanimity-majority of `roundStartFacilitators` outside
    * bootstrap; see `QuorumDenominatorShrink.decide`). Used ONLY where a snapshot is actually COMMITTED -- the phase gate
    * `maybeGetAllDeclarations` and the dag-l0 finalization gate -- so a Core that has shrunk to a cluster-minority can never finalize a
    * divergent snapshot (the proven 2-of-5 fork). The floor is over the FROZEN round committee, so a mid-round Core-narrowing
    * TimeoutCertificate cannot lower it.
    */
  def quorumFinalityDecision(
    state: ConsensusState[Key, Status, Outcome, Kind]
  )(implicit asyncF: Async[F]): F[QuorumDenominatorShrink.Decision] =
    decideQuorum(state, applyClusterFloor = clusterFloorActive(state))

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
    // matching the floored denominator in `QuorumDenominatorShrink.decide`. This fences the proven
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
    // mirrors `decision.baseQuorum` for the active gate set (Core in bootstrap, committee otherwise).
    val quorumFraction = config.quorumThresholdFraction
    val quorumThreshold = math.max(1, QuorumPolicy.fromFraction(gateSize, quorumFraction))
    val gateDeclared: Set[PeerId] = declarationsMap.keySet.filter(gateSet.contains)

    for {
      // v33 quorum-denominator shrink: when the cluster has been silent at this key past the
      // deterministic escalation threshold, the phase gate may pass on `requiredQuorum`
      // anchor-member declarations instead of the full quorum. Inert (decision.meets ==
      // `gateReceivedCount >= quorumThreshold`) in normal operation; outside bootstrap the rung is
      // neutralized by the cluster floor (no shrink below the committee majority).
      decision <- quorumFinalityDecision(state)
      met = decision.meets(gateDeclared)
      shrunk = decision.shrunkPath(gateDeclared)
      result <-
        if (met) {
          logger.debug(
            s"Quorum reached: ${gateReceivedCount}/${gateSize} committee declared (total received ${receivedCount}/${collectionUniverse.size}, need ${quorumThreshold}) for key=${state.key}"
          ) >>
            logger
              .info(
                s"[QuorumShrink] phase gate passed via shrunken quorum for key=${state.key}: " +
                  s"declared=${gateReceivedCount}/${gateSize} base=${decision.baseQuorum} required=${decision.requiredQuorum} " +
                  s"steps=${decision.steps} anchorSize=${decision.anchor.size}"
              )
              .whenA(shrunk) >>
            declarationsMap.some.pure[F]
        } else {
          none[SortedMap[PeerId, A]].pure[F]
        }
    } yield result
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
