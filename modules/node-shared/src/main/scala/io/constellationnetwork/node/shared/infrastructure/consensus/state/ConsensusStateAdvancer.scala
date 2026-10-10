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

  /** Whether the chain is still in bootstrap (no recent committee reached `BootstrapCommittee.MinimumSize`).
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

  /** Supermajority of the frozen round committee. Every phase gate, certificate, and the finalization gate use this one quorum. */
  def committeeQuorum(state: ConsensusState[Key, Status, Outcome, Kind]): Int =
    FinalityQuorum.of(state.roundStartFacilitators.value.size, config.quorumThresholdFraction)

  protected def maybeGetAllDeclarations[A](
    state: State,
    resources: Resources
  )(
    getter: PeerDeclarations => Option[A]
  )(implicit asyncF: Async[F]): F[Option[SortedMap[PeerId, A]]] = {
    // The gate counts declarations from the frozen round committee. Collection also unions the mutable active set
    // defensively; a member withdrawn mid-round stays in the frozen universe so its declaration still counts.
    val gateSet = state.roundStartFacilitators.value.toSet
    val gateSize = gateSet.size
    val collectionUniverse: Set[PeerId] = state.facilitators.value.toSet ++ gateSet

    val declarations = collectionUniverse.flatMap { peerId =>
      resources.peerDeclarationsMap
        .get(peerId)
        .flatMap(getter)
        .map((peerId, _))
    }

    val declarationsMap = SortedMap.from(declarations)
    val receivedCount = declarationsMap.size
    val gateReceivedCount = declarationsMap.keys.count(gateSet.contains)

    val gateDeclared: Set[PeerId] = declarationsMap.keySet.filter(gateSet.contains)
    val required = committeeQuorum(state)

    if (gateDeclared.size >= required)
      logger.debug(
        s"Quorum reached: ${gateReceivedCount}/${gateSize} committee declared (total received ${receivedCount}/${collectionUniverse.size}, need ${required}) for key=${state.key}"
      ) >> declarationsMap.some.pure[F]
    else none[SortedMap[PeerId, A]].pure[F]
  }
}
