package io.constellationnetwork.currency.l0.snapshot.synchronous

import cats._
import cats.effect.Sync
import cats.syntax.all._

import io.constellationnetwork.currency.l0.snapshot.synchronous.ConsensusStorage.ModifyStateWithEffectFn
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

  protected def toCreateStateWithEffectFn(
    fn: F[(ConsensusState[Key, Status, Outcome, Kind], F[Unit])]
  ): ModifyStateWithEffectFn[F, Key, Status, Outcome, Kind, StateCreateResult] = {
    case None =>
      fn.map {
        case (state, effect) => (state.some, state.some, effect).some
      }
    case Some(_) => none.pure[F]
  }

  protected def logIfCreatedState(createResult: StateCreateResult): F[Unit] =
    createResult.traverse { state =>
      logger.info(s"State created ${state.show}")
    }.void
}
