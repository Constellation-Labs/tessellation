package org.tessellation.node.shared.infrastructure.consensus

import cats._
import cats.effect.Async
import cats.effect.kernel.Clock
import cats.kernel.Next
import cats.syntax.all._

import scala.reflect.runtime.universe.TypeTag

import org.tessellation.ext.cats.syntax.next._
import org.tessellation.node.shared.domain.consensus.ConsensusFunctions
import org.tessellation.node.shared.domain.gossip.Gossip
import org.tessellation.node.shared.domain.seedlist.SeedlistEntry
import org.tessellation.node.shared.infrastructure.consensus.declaration.{Facility, kind}
import org.tessellation.node.shared.infrastructure.consensus.message.ConsensusPeerDeclaration
import org.tessellation.node.shared.infrastructure.consensus.trigger.ConsensusTrigger
import org.tessellation.schema.peer.PeerId

import io.circe.Encoder
import org.typelevel.log4cats.slf4j.Slf4jLogger

trait ConsensusStateCreator[F[_], Key, Artifact, Context] {

  type StateCreateResult = Option[ConsensusState[Key, Artifact, Context]]

  /** Tries to facilitate consensus. Returns `Some(state)` if state with `key` didn't exist, otherwise returns `None`
    */
  def tryFacilitateConsensus(
    key: Key,
    lastOutcome: ConsensusOutcome[Key, Artifact, Context],
    maybeTrigger: Option[ConsensusTrigger],
    resources: ConsensusResources[Artifact]
  ): F[StateCreateResult]

}

object ConsensusStateCreator {
  def make[F[
    _
  ]: Async, Event, Key: Show: Next: TypeTag: Encoder, Artifact, Context](
    consensusFns: ConsensusFunctions[F, Event, Key, Artifact, Context],
    consensusStorage: ConsensusStorage[F, Event, Key, Artifact, Context],
    gossip: Gossip[F],
    selfId: PeerId,
    seedlist: Option[Set[SeedlistEntry]]
  ): ConsensusStateCreator[F, Key, Artifact, Context] = new ConsensusStateCreator[F, Key, Artifact, Context] {

    private val logger = Slf4jLogger.getLoggerFromClass(ConsensusStateCreator.getClass)

    def tryFacilitateConsensus(
      key: Key,
      lastOutcome: ConsensusOutcome[Key, Artifact, Context],
      maybeTrigger: Option[ConsensusTrigger],
      resources: ConsensusResources[Artifact]
    ): F[StateCreateResult] =
      tryCreateNewConsensus(key, facilitateConsensus(key, lastOutcome, maybeTrigger, resources))

    private def tryCreateNewConsensus(
      key: Key,
      fn: F[(ConsensusState[Key, Artifact, Context], F[Unit])]
    ): F[StateCreateResult] =
      consensusStorage
        .condModifyState(key)(toCreateStateFn(fn))
        .flatMap(evalEffect)
        .flatTap(logIfCreatedState)

    import consensusStorage.ModifyStateFn

    private def toCreateStateFn(
      fn: F[(ConsensusState[Key, Artifact, Context], F[Unit])]
    ): ModifyStateFn[(StateCreateResult, F[Unit])] = {
      case None =>
        fn.map {
          case (state, effect) => (state.some, (state.some, effect)).some
        }
      case Some(_) => none.pure[F]
    }

    private def evalEffect(maybeResultAndEffect: Option[(StateCreateResult, F[Unit])]): F[StateCreateResult] =
      maybeResultAndEffect.flatTraverse { case (result, effect) => effect.as(result) }

    private def logIfCreatedState(createResult: StateCreateResult): F[Unit] =
      createResult.traverse { state =>
        logger.info(s"State created ${state.show}")
      }.void

    private def facilitateConsensus(
      key: Key,
      lastOutcome: ConsensusOutcome[Key, Artifact, Context],
      maybeTrigger: Option[ConsensusTrigger],
      resources: ConsensusResources[Artifact]
    ): F[(ConsensusState[Key, Artifact, Context], F[Unit])] =
      for {

        candidates <- consensusStorage.getCandidates(key.next)

        facilitators <- lastOutcome.facilitators
          .concat(lastOutcome.status.candidates)
          .filter(peerId => seedlist.forall(_.map(_.peerId).contains(peerId)))
          .filterA(consensusFns.facilitatorFilter(lastOutcome.status.signedMajorityArtifact, lastOutcome.status.context, _))
          .map(_.prepended(selfId).distinct.sorted)

        (withdrawn, remained) = facilitators.partition { peerId =>
          resources.withdrawalsMap.get(peerId).contains(kind.Facility)
        }

        time <- Clock[F].monotonic
        effect = consensusStorage.getUpperBound.flatMap { bound =>
          gossip.spread(
            ConsensusPeerDeclaration(
              key,
              Facility(bound, candidates.toSet, maybeTrigger, lastOutcome.status.facilitatorsHash)
            )
          )
        }
        state = ConsensusState[Key, Artifact, Context](
          key,
          lastOutcome,
          remained,
          CollectingFacilities[Artifact, Context](
            maybeTrigger,
            lastOutcome.status.facilitatorsHash
          ),
          time,
          withdrawnFacilitators = withdrawn.toSet
        )
      } yield (state, effect)

  }
}
