package io.constellationnetwork.node.shared.infrastructure.consensus.engine

import cats.effect.kernel.Async
import cats.effect.std.Queue
import cats.syntax.all._

import io.constellationnetwork.node.shared.domain.node.NodeStorage
import io.constellationnetwork.node.shared.infrastructure.consensus.ConsensusStorage
import io.constellationnetwork.node.shared.infrastructure.consensus.engine.ConsensusCommand.{RollbackStartPolicy, _}
import io.constellationnetwork.schema.node.NodeState
import io.constellationnetwork.security.signature.Signed

/** External API for consensus engine operations.
  *
  * This is a thin facade that translates external calls into commands on the queue. It provides a clean interface for other parts of the
  * system to interact with consensus.
  *
  * ==Operations==
  *
  * '''registerForConsensus(key):''' Called when node wants to start participating. Sets observation key in storage and updates node state.
  *
  * '''startFacilitatingAfterDownload(key, artifact, context):''' Called after node downloads state from cluster. Enqueues
  * `InitializeFromDownload` which fetches the full outcome from peers and starts participating.
  *
  * '''startFacilitatingAfterRollback(key, outcome):''' Called after node rolls back to a previous state. Enqueues `InitializeFromRollback`
  * to resume consensus from the given outcome, optionally deferring the first round.
  *
  * '''withdrawFromConsensus():''' Called when node wants to leave. Enqueues `WithdrawFromConsensus` which spreads withdrawal declaration
  * and cleans up state.
  *
  * @note
  *   All methods are non-blocking - they just enqueue commands
  * @see
  *   ConsensusEventLoop for how commands are processed
  */

trait ConsensusManager[F[_], Event, Key, Artifact, Context, Status, Outcome, Kind] {
  def registerForConsensus(observationKey: Key): F[Unit]
  def resetForRecovery: F[Unit]
  def startFacilitatingAfterDownload(key: Key, lastArtifact: Signed[Artifact], lastContext: Context, isRecovery: Boolean = false): F[Unit]
  def startFacilitatingAfterRollback(
    lastKey: Key,
    initialOutcome: Outcome,
    startPolicy: RollbackStartPolicy = RollbackStartPolicy.Immediate
  ): F[Unit]
  def withdrawFromConsensus: F[Unit]
}

object ConsensusManager {

  def make[F[_]: Async, Event, Key, Artifact, Context, Status, Outcome, Kind](
    queue: Queue[F, ConsensusCommand[Key, Artifact, Context, Outcome]],
    storage: ConsensusStorage[F, Event, Key, Artifact, Context, Status, Outcome, Kind],
    nodeStorage: NodeStorage[F]
  ): F[ConsensusManager[F, Event, Key, Artifact, Context, Status, Outcome, Kind]] =
    Async[F].pure {
      new ConsensusManager[F, Event, Key, Artifact, Context, Status, Outcome, Kind] {

        def registerForConsensus(observationKey: Key): F[Unit] =
          storage
            .trySetObservationKey(observationKey)
            .ifM(
              ifTrue = nodeStorage.tryModifyState(NodeState.WaitingForObserving, NodeState.Observing),
              ifFalse = new Throwable("Registration failed: already registered at different key").raiseError[F, Unit]
            )

        def resetForRecovery: F[Unit] =
          storage.clearObservationKey >> storage.clearAndGetLastConsensusOutcome.void

        def startFacilitatingAfterDownload(
          key: Key,
          lastArtifact: Signed[Artifact],
          lastContext: Context,
          isRecovery: Boolean = false
        ): F[Unit] =
          queue.offer(InitializeFromDownload(key, lastArtifact, lastContext, isRecovery))

        def startFacilitatingAfterRollback(
          lastKey: Key,
          initialOutcome: Outcome,
          startPolicy: RollbackStartPolicy = RollbackStartPolicy.Immediate
        ): F[Unit] =
          queue.offer(InitializeFromRollback(lastKey, initialOutcome, startPolicy))

        def withdrawFromConsensus: F[Unit] =
          queue.offer(WithdrawFromConsensus)
      }
    }
}
