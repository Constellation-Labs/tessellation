package io.constellationnetwork.node.shared.infrastructure.consensus.state

import cats.data.StateT
import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.SortedMap

import io.constellationnetwork.node.shared.config.types.ConsensusConfig
import io.constellationnetwork.node.shared.domain.cluster.storage.ClusterStorage
import io.constellationnetwork.node.shared.infrastructure.consensus.{ConsensusResources, PeerDeclarations}
import io.constellationnetwork.schema.peer.PeerId

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

  def advanceStatus(resources: ConsensusResources[Artifact, Kind]): StateT[F, ConsensusState[Key, Status, Outcome, Kind], F[Unit]]

  def logger(implicit async: Async[F]): SelfAwareStructuredLogger[F] =
    Slf4jLogger.getLoggerFromName[F](this.getClass.getName)

  protected def clusterStorage: ClusterStorage[F]

  protected def config: ConsensusConfig

  /** Collect declarations from at least a quorum of facilitators with a supermajority safety gate.
    *
    * Returns ALL received declarations (not just quorum-size) once the quorum threshold is met AND the majority value (extracted via
    * `valueExtractor`) has >= quorumSize support. This ensures determinism across different node views — if the dominant value has
    * supermajority support, any quorum-sized subset will compute the same `pickMajority` result (pigeonhole principle).
    *
    * If quorum is reached but no value has sufficient support (split vote), returns `None` and defers to the stall detector, which will
    * lock the round and remove unresponsive peers.
    *
    * Falls back to 100% if quorumThreshold is not configured.
    */
  protected def maybeGetQuorumDeclarations[A, V](
    state: State,
    resources: Resources
  )(getter: PeerDeclarations => Option[A])(
    valueExtractor: A => V
  )(implicit asyncF: Async[F]): F[Option[SortedMap[PeerId, A]]] = {
    val activeFacilitators = state.facilitators.value
    val totalRequired = activeFacilitators.size
    val quorumSize = config.quorumThreshold match {
      case Some(threshold) => math.ceil(totalRequired * threshold).toInt.max(1)
      case None            => totalRequired
    }

    val declarations = activeFacilitators.flatMap { peerId =>
      resources.peerDeclarationsMap
        .get(peerId)
        .flatMap(getter)
        .map((peerId, _))
    }

    val declarationsMap = SortedMap.from(declarations)
    val receivedCount = declarationsMap.size

    if (receivedCount >= quorumSize) {
      val values = declarationsMap.values.toList.map(valueExtractor)
      val maxSupport = values.groupBy(identity).values.map(_.size).maxOption.getOrElse(0)

      if (maxSupport >= quorumSize) {
        logger.debug(
          s"Quorum reached: $receivedCount/$totalRequired (need $quorumSize, max_support=$maxSupport) for key=${state.key}"
        ) >>
          declarationsMap.some.pure[F]
      } else {
        logger.debug(
          s"Quorum met ($receivedCount/$totalRequired) but no safe majority (max_support=$maxSupport < quorum=$quorumSize) for key=${state.key}"
        ) >>
          none[SortedMap[PeerId, A]].pure[F]
      }
    } else {
      none[SortedMap[PeerId, A]].pure[F]
    }
  }
}
