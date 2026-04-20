package io.constellationnetwork.node.shared.infrastructure.consensus.state

import cats.data.StateT
import cats.effect.{Async, Clock}
import cats.syntax.all._

import scala.collection.immutable.SortedMap
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

  protected def maybeGetAllDeclarations[A](
    state: State,
    resources: Resources
  )(
    getter: PeerDeclarations => Option[A]
  )(implicit asyncF: Async[F]): F[Option[SortedMap[PeerId, A]]] = {
    val activeFacilitators = state.facilitators.value
    val totalRequired = activeFacilitators.size

    val declarations = activeFacilitators.flatMap { peerId =>
      resources.peerDeclarationsMap
        .get(peerId)
        .flatMap(getter)
        .map((peerId, _))
    }

    val declarationsMap = SortedMap.from(declarations)
    val receivedCount = declarationsMap.size

    // Quorum threshold from config. Default: unanimity (1.0 = all must respond).
    // Testnet/mainnet use 0.67 (supermajority) so community peers don't block rounds.
    // Dev uses 1.0 (unanimity) for clean 5-node E2E convergence.
    val quorumFraction = config.quorumThresholdFraction
    val quorumThreshold = math.max(1, math.ceil(totalRequired * quorumFraction).toInt)

    for {
      result <-
        if (receivedCount >= quorumThreshold) {
          logger.debug(
            s"Quorum reached: ${receivedCount}/${totalRequired} declared (need ${quorumThreshold}) for key=${state.key}"
          ) >>
            declarationsMap.some.pure[F]
        } else {
          none[SortedMap[PeerId, A]].pure[F]
        }
    } yield result
  }
}
