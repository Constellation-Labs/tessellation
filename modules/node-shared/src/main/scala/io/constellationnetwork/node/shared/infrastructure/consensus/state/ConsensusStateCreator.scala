package io.constellationnetwork.node.shared.infrastructure.consensus.state

import cats._
import cats.effect.Sync
import cats.syntax.all._

import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.ConsensusTrigger
import io.constellationnetwork.node.shared.infrastructure.consensus.{ConsensusResources, ConsensusStorage}

import org.typelevel.log4cats.slf4j.Slf4jLogger

/** Creates new consensus states when a round begins.
  *
  * ==When Called==
  *
  * Called by RoundRunner.facilitateRound() when starting a new consensus round.
  *
  * ==What It Does==
  *
  *   1. Checks if state already exists for this key (returns None if so) 2. Selects facilitators (peers who are Ready) 3. Creates
  *      ConsensusState with status=CollectingFacilities 4. Computes our Facility declaration (events, tips, trigger) 5. Spreads Facility
  *      declaration via gossip 6. Stores new state
  *
  * ==Subclassing==
  *
  * Abstract class with protected helpers. Subclasses implement:
  *   - How to select facilitators
  *   - How to build facility info
  *
  * @see
  *   GlobalSnapshotConsensusStateCreator for global L0 implementation
  */
abstract class ConsensusStateCreator[F[_]: Sync, Key: Show, Artifact, Context, Status: Show, Outcome, Kind: Show] {

  type StateCreateResult = Option[ConsensusState[Key, Status, Outcome, Kind]]

  def tryFacilitateConsensus(
    key: Key,
    lastOutcome: Outcome,
    maybeTrigger: Option[ConsensusTrigger],
    resources: ConsensusResources[Artifact, Kind]
  ): F[StateCreateResult]

  private val logger = Slf4jLogger.getLogger[F]

  protected def toCreateStateFn(
    fn: F[(ConsensusState[Key, Status, Outcome, Kind], F[Unit])]
  ): ConsensusStorage.ModifyStateFn[F, Key, Status, Outcome, Kind, (StateCreateResult, F[Unit])] = {
    case None =>
      fn.map { case (state, effect) => (state.some, (state.some, effect)).some }
    case Some(_) =>
      none.pure[F]
  }

  protected def evalEffect(maybeResultAndEffect: Option[(StateCreateResult, F[Unit])]): F[StateCreateResult] =
    maybeResultAndEffect.flatTraverse { case (result, effect) => effect.as(result) }

  protected def logIfCreated(createResult: StateCreateResult): F[Unit] =
    Applicative[F].unit
}
