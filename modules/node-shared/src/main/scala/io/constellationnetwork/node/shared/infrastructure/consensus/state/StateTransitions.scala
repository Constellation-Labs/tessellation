package io.constellationnetwork.node.shared.infrastructure.consensus.state

import cats.effect.kernel.Async
import cats.effect.std.Random
import cats.syntax.all._

import scala.concurrent.duration._

import io.constellationnetwork.node.shared.infrastructure.consensus.engine.ConsensusCommand._
import io.constellationnetwork.node.shared.infrastructure.consensus.message.GetConsensusOutcomeRequest
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger._
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.schema.node.NodeState
import io.constellationnetwork.schema.peer.Peer
import io.constellationnetwork.security.signature.Signed

import eu.timepit.refined.auto._
import monocle.Lens
import retry.RetryPolicies.{constantDelay, limitRetries}
import retry.syntax.all._

/** Handles state transitions and lifecycle operations for consensus.
  *
  * ==Purpose==
  *
  * Contains the "business logic" for consensus state changes:
  *   - Checking for updates and advancing state
  *   - Finalizing outcomes and notifying FSM
  *   - Initialization and withdrawal
  *
  * ==Key Methods==
  *
  * '''checkUpdate(key):''' Called when new data arrives. Tries to update state and advance. If outcome is ready, calls finalizeAndNotify().
  * {{{
  *   checkUpdate(key)
  *       │
  *       ├── updater.tryUpdateConsensus(key, resources)
  *       │
  *       ├── advancer.getConsensusOutcome(newState)
  *       │     │
  *       │     ├── None → Wait for more data
  *       │     │
  *       │     └── Some((prevKey, outcome)) → finalizeAndNotify()
  *       │
  *       └── queue.offer(ConsensusFinished(...))
  * }}}
  *
  * '''finalizeAndNotify():''' Records metrics, updates storage, notifies FSM that consensus finished.
  *
  * '''initFromDownload(key, artifact, context):''' Fetches outcome from cluster peers, initializes storage, starts first round.
  *
  * '''initFromRollback(key, outcome):''' Sets outcome in storage, starts first round.
  *
  * '''withdraw():''' Spreads withdrawal declaration, cleans up state.
  *
  * '''registerPeer(peer):''' Registers newly observed peer for current consensus round.
  *
  * @see
  *   ConsensusStateUpdater for update logic
  * @see
  *   ConsensusStateAdvancer for advancement logic
  */
class StateTransitions[F[_]: Async: Random: Metrics, Event, Key, Artifact, Ctx, Status, Outcome, Kind](
  ctx: ConsensusEngineContext[F, Event, Key, Artifact, Ctx, Status, Outcome, Kind]
)(
  implicit outcomeKey: Lens[Outcome, Key],
  outcomeArtifact: Lens[Outcome, Signed[Artifact]],
  outcomeContext: Lens[Outcome, Ctx],
  outcomeTrigger: Lens[Outcome, ConsensusTrigger]
) {

  import ctx.{advancer, logger => log, queue, remover, storage, updater}

  def checkUpdate(key: Key): F[Unit] =
    storage.getResources(key).flatMap { resources =>
      updater.tryUpdateConsensus(key, resources).flatMap {
        case None =>
          Async[F].unit

        case Some((_, newState)) =>
          advancer.getConsensusOutcome(newState) match {
            case Some((prevKey, outcome)) =>
              finalizeAndNotify(newState, prevKey, outcome)

            case None =>
              log.debug(s"State updated for key=$key, status=${newState.status}")
          }
      }
    }

  private def finalizeAndNotify(
    newState: ConsensusState[Key, Status, Outcome, Kind],
    prevKey: Previous[Key],
    outcome: Outcome
  ): F[Unit] =
    for {
      now <- Async[F].monotonic
      _ <- Metrics[F].recordTime("dag_consensus_duration", now - newState.createdAt)

      updated <- storage.tryUpdateLastConsensusOutcomeWithCleanup(prevKey, outcome)

      _ <-
        if (updated) {
          val key = outcomeKey.get(outcome)
          val trigger = outcomeTrigger.get(outcome)

          log.info(s"Consensus reached outcome at key=$key") >>
            ctx.nodeStorage.tryModifyStateGetResult(NodeState.WaitingForReady, NodeState.Ready).void >>
            queue.offer(ConsensusFinished(key, outcome, trigger))
        } else {
          log.warn("Could not update last outcome; another thread may have finalized.")
        }
    } yield ()

  def registerPeer(peer: Peer): F[Unit] =
    storage.getLastConsensusOutcome.flatMap {
      case None => Async[F].unit
      case Some(outcome) =>
        storage.registerPeer(peer.id, outcomeKey.get(outcome)).void.handleError(_ => ())
    }

  def withdraw: F[Unit] =
    for {
      maybeOutcome <- storage.getLastConsensusOutcome
      _ <- maybeOutcome.traverse_ { outcome =>
        val key = outcomeKey.get(outcome)
        remover.withdrawFromConsensus(key)
      }
      _ <- storage.clearObservationKey
      _ <- ctx.nodeStorage.tryModifyState(NodeState.Observing, NodeState.Ready)
    } yield ()

  def initFromDownload(key: Key, artifact: Signed[Artifact], context: Ctx): F[Unit] =
    for {
      _ <- log.info(s"[DownloadInit] Initializing consensus at key=$key")
      outcome <- fetchOutcomeFromCluster(key, artifact, context)
        .flatMap(_.liftTo[F](new Throwable(s"[DownloadInit] Could not observe outcome for key=$key")))
      success <- storage.trySetInitialConsensusOutcome(outcome)
      _ <-
        if (success)
          ctx.nodeStorage.tryModifyState(NodeState.Observing, NodeState.WaitingForReady) >>
            queue.offer(StartRound(none))
        else
          new Throwable(s"[DownloadInit] Failed to initialize consensus storage").raiseError[F, Unit]
    } yield ()

  def initFromRollback(key: Key, outcome: Outcome): F[Unit] =
    for {
      _ <- log.info(s"[RollbackInit] Initializing consensus after rollback at key=$key")
      _ <- storage.trySetInitialConsensusOutcome(outcome)
      _ <- queue.offer(StartRound(TimeTrigger.some))
    } yield ()

  private def fetchOutcomeFromCluster(key: Key, artifact: Signed[Artifact], context: Ctx): F[Option[Outcome]] = {
    val retryPolicy = limitRetries(10).join(constantDelay(3.seconds))

    def selectPeer: F[Peer] =
      ctx.clusterStorage.getResponsivePeers
        .map(_.filter(_.state == NodeState.Ready).toSeq)
        .flatMap(Random[F].elementOf)

    def fetch(peer: Peer): F[Option[Outcome]] =
      ctx.consensusClient.getSpecificConsensusOutcome(GetConsensusOutcomeRequest(key)).run(peer)

    def isValid(outcome: Option[Outcome]): F[Boolean] =
      outcome
        .exists(o =>
          outcomeKey.get(o) == key &&
            outcomeArtifact.get(o) == artifact &&
            outcomeContext.get(o) == context
        )
        .pure[F]

    (selectPeer >>= fetch).retryingOnFailuresAndAllErrors(
      wasSuccessful = isValid,
      policy = retryPolicy,
      onFailure = (_, _) => log.info(s"[DownloadInit] Retrying for key=$key"),
      onError = (err, _) => log.error(err)("[DownloadInit] Error fetching outcome")
    )
  }
}
