package io.constellationnetwork.node.shared.infrastructure.consensus

import cats._
import cats.effect.Sync
import cats.syntax.all._

import io.constellationnetwork.node.shared.infrastructure.consensus.ConsensusStorage.ModifyStateFn
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.ConsensusTrigger

import org.typelevel.log4cats.slf4j.Slf4jLogger

abstract class ConsensusStateCreator[F[_]: Sync, Key: Show, Artifact, Context, Status: Show, Outcome, Kind: Show] {

  type StateCreateResult = Option[ConsensusState[Key, Status, Outcome, Kind]]

  /** Tries to facilitate consensus. Returns `Some(state)` if state with `key` didn't exist, otherwise returns `None`
    */
  def tryFacilitateConsensus(
    key: Key,
    lastOutcome: Outcome,
    maybeTrigger: Option[ConsensusTrigger],
    resources: ConsensusResources[Artifact, Kind]
  ): F[StateCreateResult]

  private val logger = Slf4jLogger.getLogger[F]

  protected def toCreateStateFn(
    fn: F[(ConsensusState[Key, Status, Outcome, Kind], F[Unit])]
  ): ModifyStateFn[F, Key, Status, Outcome, Kind, (StateCreateResult, F[Unit])] = {
    case None =>
      fn.map {
        case (state, effect) => (state.some, (state.some, effect)).some
      }
    case Some(_) => none.pure[F]
  }

  protected def evalEffect(maybeResultAndEffect: Option[(StateCreateResult, F[Unit])]): F[StateCreateResult] =
    maybeResultAndEffect.flatTraverse { case (result, effect) => effect.as(result) }

  protected def logIfCreatedState(createResult: StateCreateResult): F[Unit] =
    createResult.traverse { state =>
      logger.info(s"State created ${state.show}")
    }.void
}
