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

  /** Single quorum-shrink derivation shared by every consumer (phase quorums below, the cert assembly/apply sites in `StateTransitions`,
    * the proposal-embedded cert validation in both layer advancers, and the `StallDetector` feasibility gates) so the rung cannot drift
    * between call sites. Pure except for the wall-clock read. With `config.quorumShrinkActivationViews <= 0` (the default) or absent
    * evidence/parent-end anchors the returned decision is inert and every gate is byte-identical to pre-rung behavior.
    */
  def quorumShrinkDecision(
    state: ConsensusState[Key, Status, Outcome, Kind]
  )(implicit asyncF: Async[F]): F[QuorumDenominatorShrink.Decision] =
    Clock[F].realTime.map { now =>
      QuorumDenominatorShrink.decide(
        coreSize = state.coreFacilitators.value.size,
        quorumThresholdFraction = config.quorumThresholdFraction,
        latestEvidenceSigners = latestEvidenceSigners(state.lastOutcome),
        roundStartFacilitators = state.roundStartFacilitators.value.toSet,
        parentEndTimeMs = lastOutcomeEndTimeMs(state.lastOutcome),
        nowMs = now.toMillis,
        viewIntervalMs = config.viewInterval.toMillis,
        activationViews = config.quorumShrinkActivationViews
      )
    }

  protected def maybeGetAllDeclarations[A](
    state: State,
    resources: Resources
  )(
    getter: PeerDeclarations => Option[A]
  )(implicit asyncF: Async[F]): F[Option[SortedMap[PeerId, A]]] = {
    // v19 alpha.89: phase-quorum gates on the Core committee only. Tier 1 peers may declare
    // (Facility / MajoritySignature / BinarySignature) and their declarations are RETURNED in
    // the result so they earn rewards proportionally -- but their absence cannot block the
    // phase from advancing. Pre-alpha.89 this gated on `state.facilitators.value.size` (full
    // committee including Tier 1), which wedged overnight at alpha.88 with "3 active < 4
    // required" when source nodes were signing but community Tier 1 peers stayed silent.
    //
    // Collection: still iterate the full active set so Tier 1 declarations land in the result.
    // Gate: count only Core declarations against `ceil(coreSize * quorumFraction)`.
    val activeFacilitators = state.facilitators.value
    val coreSet = state.coreFacilitators.value.toSet
    val coreSize = state.coreFacilitators.value.size

    val declarations = activeFacilitators.flatMap { peerId =>
      resources.peerDeclarationsMap
        .get(peerId)
        .flatMap(getter)
        .map((peerId, _))
    }

    val declarationsMap = SortedMap.from(declarations)
    val receivedCount = declarationsMap.size
    val coreReceivedCount = declarationsMap.keys.count(coreSet.contains)

    // Quorum threshold from config. Default: unanimity (1.0 = all must respond).
    // Testnet/mainnet use 0.67 (supermajority) so community peers don't block rounds.
    // Dev uses 1.0 (unanimity) for clean E2E convergence. With the Core-only denominator
    // and quorumFraction=1.0, this requires ALL Core peers to declare -- still strict but
    // small (Core=3 in testnet). Integer arithmetic via `QuorumPolicy.fromFraction` removes
    // the `Double` from consensus math; the value is identical to the legacy
    // `ceil(coreSize * fraction)` for every n in the operating range (see `QuorumPolicySuite`).
    val quorumFraction = config.quorumThresholdFraction
    val quorumThreshold = math.max(1, QuorumPolicy.fromFraction(coreSize, quorumFraction))
    val coreDeclared: Set[PeerId] = declarationsMap.keySet.filter(coreSet.contains)

    for {
      // v33 quorum-denominator shrink: when the cluster has been silent at this key past the
      // deterministic escalation threshold, the phase gate may pass on `requiredQuorum`
      // anchor-member declarations instead of the full Core quorum. Inert (decision.meets ==
      // `coreReceivedCount >= quorumThreshold`) in normal operation.
      decision <- quorumShrinkDecision(state)
      met = decision.meets(coreDeclared)
      shrunk = decision.shrunkPath(coreDeclared)
      result <-
        if (met) {
          logger.debug(
            s"Quorum reached: ${coreReceivedCount}/${coreSize} core declared (total received ${receivedCount}/${activeFacilitators.size}, need ${quorumThreshold} core) for key=${state.key}"
          ) >>
            logger
              .info(
                s"[QuorumShrink] phase gate passed via shrunken quorum for key=${state.key}: " +
                  s"coreDeclared=${coreReceivedCount}/${coreSize} base=${decision.baseQuorum} required=${decision.requiredQuorum} " +
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
