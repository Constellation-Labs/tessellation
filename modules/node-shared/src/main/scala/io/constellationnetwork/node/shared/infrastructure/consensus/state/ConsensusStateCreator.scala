package io.constellationnetwork.node.shared.infrastructure.consensus.state

import cats._
import cats.effect.Sync
import cats.syntax.all._

import scala.collection.immutable.SortedSet
import scala.util.control.NoStackTrace

import io.constellationnetwork.node.shared.infrastructure.consensus.ConsensusLog.{Category, Event => LogEvent}
import io.constellationnetwork.node.shared.infrastructure.consensus.declaration.Facility
import io.constellationnetwork.node.shared.infrastructure.consensus.message.ConsensusPeerDeclaration
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.ConsensusTrigger
import io.constellationnetwork.node.shared.infrastructure.consensus.{ConsensusLog, ConsensusResources, ConsensusStorage}
import io.constellationnetwork.schema.peer.PeerId

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

  /** Builds a new consensus state for `key`. `priorAbandonmentCount` is retained for compatibility and diagnostics, but implementations
    * should not treat it as certified view evidence. Round-start view should remain at the committed/certified view; subsequent view
    * movement must be driven by signed VCV quorum / assembled VCC.
    */
  def tryFacilitateConsensus(
    key: Key,
    lastOutcome: Outcome,
    maybeTrigger: Option[ConsensusTrigger],
    resources: ConsensusResources[Artifact, Kind],
    priorAbandonmentCount: Int,
    expectedRoundStartFacilitators: Option[SortedSet[PeerId]]
  ): F[StateCreateResult]

  /** Re-send this node's own, already-stored Facility declaration to the given targets.
    *
    * Used by StallDetector to recover from lost Facility rumors when a round is stuck in CollectingFacilities. Reads the stored declaration
    * from ConsensusStorage — never recomputes from fresh inputs — so retransmits are byte-equivalent to the original. No-op if no Facility
    * was stored for (selfId, key) yet.
    */
  def retransmitOwnFacility(key: Key, targets: Set[PeerId]): F[Unit]

  private val logger = Slf4jLogger.getLogger[F]

  protected def toCreateStateFn(
    fn: F[(ConsensusState[Key, Status, Outcome, Kind], F[Unit])]
  ): ConsensusStorage.ModifyStateFn[F, Key, Status, Outcome, Kind, (StateCreateResult, F[Unit])] = {
    case None =>
      fn.map { case (state, effect) => (state.some, (state.some, effect)).some }
    case Some(_) =>
      none.pure[F]
  }

  /** Validate an operator/startup expectation before ConsensusStorage commits the state and runs its retained Facility effect.
    *
    * The expectation is local orchestration input, not committee authority. The concrete state creator still derives the committee through
    * its ordinary deterministic pipeline. A mismatch therefore fails closed before self-store or direct delivery can expose a Facility for
    * a round that the startup barrier did not authorize.
    */
  protected def validateExpectedRoundStartFacilitators(
    created: F[(ConsensusState[Key, Status, Outcome, Kind], F[Unit])],
    expected: Option[SortedSet[PeerId]]
  ): F[(ConsensusState[Key, Status, Outcome, Kind], F[Unit])] =
    created.flatMap {
      case result @ (state, _) =>
        val actual = SortedSet.from(state.roundStartFacilitators.value)
        ConsensusStateCreator
          .validateExpectedRoundStartFacilitators(expected, actual)
          .liftTo[F]
          .as(result)
    }

  protected def logIfCreated(createResult: StateCreateResult): F[Unit] =
    createResult.traverse_(state =>
      ConsensusLog.info(
        logger,
        Category.Lifecycle,
        state.key.show,
        "n/a",
        LogEvent.StateCreated,
        "leader" -> ConsensusLog.pid(state.leader),
        "facilitators" -> state.facilitators.value.size.toString,
        "view" -> state.viewNumber.toString
      )
    )
}

object ConsensusStateCreator {

  final case class UnexpectedRoundStartFacilitators(expected: SortedSet[PeerId], actual: SortedSet[PeerId]) extends NoStackTrace {
    override def getMessage: String =
      s"Derived first-round committee does not match the held startup expectation: expected=${expected.size} actual=${actual.size}"
  }

  /** Pure boundary check shared by Global L0 state-creation paths. Keeping comparison here prevents startup modes and tests from acquiring
    * subtly different set/order semantics.
    */
  private[consensus] def validateExpectedRoundStartFacilitators(
    expected: Option[SortedSet[PeerId]],
    actual: SortedSet[PeerId]
  ): Either[UnexpectedRoundStartFacilitators, Unit] =
    expected match {
      case Some(value) if value =!= actual => Left(UnexpectedRoundStartFacilitators(value, actual))
      case _                               => Right(())
    }

  /** Build the replayable post-commit operation from values captured before the state commit. Callers must perform every dynamic read
    * before constructing `facility` and `declaration`; a retained retry then only repeats the exact self-store and direct delivery.
    */
  private[constellationnetwork] def exactFacilityEffect[F[_]: Monad, Key](
    facility: Facility,
    declaration: ConsensusPeerDeclaration[Key, Facility],
    targets: Set[PeerId]
  )(
    selfStore: Facility => F[Unit],
    spreadDirect: (ConsensusPeerDeclaration[Key, Facility], Set[PeerId]) => F[Unit]
  ): F[Unit] =
    selfStore(facility) >> spreadDirect(declaration, targets)
}
