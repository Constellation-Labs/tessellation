package org.tessellation.sdk.infrastructure.consensus

import cats._
import cats.effect.Async
import cats.effect.kernel.Clock
import cats.syntax.all._

import scala.reflect.runtime.universe.TypeTag

import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.domain.consensus.ConsensusFunctions
import org.tessellation.sdk.domain.gossip.Gossip
import org.tessellation.sdk.infrastructure.consensus.declaration.Facility
import org.tessellation.sdk.infrastructure.consensus.message.ConsensusPeerDeclaration
import org.tessellation.sdk.infrastructure.consensus.trigger.ConsensusTrigger

import io.circe.Encoder
import org.typelevel.log4cats.slf4j.Slf4jLogger

trait ConsensusStateCreator[F[_], Key, Artifact] {

  type StateCreateResult = Option[ConsensusState[Key, Artifact]]

  /** Tries to facilitate consensus. Returns `Some(state)` if state with `key` didn't exist, otherwise returns `None`
    */
  def tryFacilitateConsensus(
    key: Key,
    lastKey: Key,
    lastStatus: Finished[Artifact],
    maybeTrigger: Option[ConsensusTrigger],
    resources: ConsensusResources[Artifact]
  ): F[StateCreateResult]

  /** Tries to observe consensus. Returns `Some(state)` if state with `key` didn't exist, otherwise returns `None`
    */
  def tryObserveConsensus(
    key: Key,
    lastKey: Key,
    resources: ConsensusResources[Artifact],
    initialPeer: PeerId
  ): F[StateCreateResult]

}

object ConsensusStateCreator {
  def make[F[
    _
  ]: Async: Clock, Event, Key: Show: TypeTag: Encoder, Artifact](
    consensusFns: ConsensusFunctions[F, Event, Key, Artifact],
    consensusStorage: ConsensusStorage[F, Event, Key, Artifact],
    facilitatorCalculator: FacilitatorCalculator,
    gossip: Gossip[F],
    selfId: PeerId
  ): ConsensusStateCreator[F, Key, Artifact] = new ConsensusStateCreator[F, Key, Artifact] {

    private val logger = Slf4jLogger.getLoggerFromClass(ConsensusStateCreator.getClass)

    def tryFacilitateConsensus(
      key: Key,
      lastKey: Key,
      lastStatus: Finished[Artifact],
      maybeTrigger: Option[ConsensusTrigger],
      resources: ConsensusResources[Artifact]
    ): F[StateCreateResult] =
      tryCreateNewConsensus(key, facilitateConsensus(key, lastKey, lastStatus, maybeTrigger, resources))

    def tryObserveConsensus(
      key: Key,
      lastKey: Key,
      resources: ConsensusResources[Artifact],
      initialPeer: PeerId
    ): F[StateCreateResult] =
      tryCreateNewConsensus(key, observeConsensus(key, lastKey, resources, initialPeer))

    private def tryCreateNewConsensus(
      key: Key,
      fn: F[(ConsensusState[Key, Artifact], F[Unit])]
    ): F[StateCreateResult] =
      consensusStorage
        .condModifyState(key)(toCreateStateFn(fn))
        .flatMap(evalEffect)
        .flatTap(logIfCreatedState)

    import consensusStorage.ModifyStateFn

    private def toCreateStateFn(
      fn: F[(ConsensusState[Key, Artifact], F[Unit])]
    ): ModifyStateFn[(StateCreateResult, F[Unit])] = {
      case None =>
        fn.map {
          case (state, effect) => (state.some, (state.some, effect)).some
        }
      case Some(_) => none.pure[F]
    }

    private def evalEffect(maybeResultAndEffect: Option[(StateCreateResult, F[Unit])]): F[StateCreateResult] =
      maybeResultAndEffect.flatTraverse { case (result, effect) => effect.map(_ => result) }

    private def logIfCreatedState(createResult: StateCreateResult): F[Unit] =
      createResult.traverse { state =>
        logger.info(s"State created ${state.show}")
      }.void

    private def facilitateConsensus(
      key: Key,
      lastKey: Key,
      lastStatus: Finished[Artifact],
      maybeTrigger: Option[ConsensusTrigger],
      resources: ConsensusResources[Artifact]
    ): F[(ConsensusState[Key, Artifact], F[Unit])] =
      for {
        registeredPeers <- consensusStorage.getRegisteredPeers(key)
        localFacilitators <- (selfId :: registeredPeers).filterA(consensusFns.facilitatorFilter(lastStatus.signedMajorityArtifact, _))
        facilitators = facilitatorCalculator.calculate(
          resources.peerDeclarationsMap,
          localFacilitators
        )
        time <- Clock[F].monotonic
        effect = consensusStorage.getUpperBound.flatMap { bound =>
          gossip.spread(
            ConsensusPeerDeclaration(
              key,
              Facility(bound, localFacilitators.toSet, maybeTrigger, lastStatus.facilitatorsHash)
            )
          )
        }
        state = ConsensusState(
          key,
          lastKey,
          facilitators,
          CollectingFacilities(
            FacilityInfo[Artifact](lastStatus.signedMajorityArtifact, maybeTrigger, lastStatus.facilitatorsHash).some
          ),
          time
        )
      } yield (state, effect)

    private def observeConsensus(
      key: Key,
      lastKey: Key,
      resources: ConsensusResources[Artifact],
      initialPeer: PeerId
    ): F[(ConsensusState[Key, Artifact], F[Unit])] =
      Clock[F].monotonic.map { time =>
        val facilitators = facilitatorCalculator.calculate(
          resources.peerDeclarationsMap,
          List(initialPeer)
        )
        val state = ConsensusState(
          key,
          lastKey,
          facilitators,
          CollectingFacilities(none[FacilityInfo[Artifact]]),
          time
        )
        val effect = Applicative[F].unit
        (state, effect)
      }

  }
}
