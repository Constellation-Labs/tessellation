package io.constellationnetwork.node.shared.infrastructure.consensus.state

import cats.effect.kernel.Async
import cats.effect.std.Random
import cats.syntax.all._
import cats.{Eq, Show}

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
import retry.RetryDetails
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
class StateTransitions[F[_]: Async: Random: Metrics, Event, Key: Eq: Show, Artifact: Eq, Ctx: Eq, Status, Outcome, Kind](
  ctx: ConsensusEngineContext[F, Event, Key, Artifact, Ctx, Status, Outcome, Kind]
)(
  implicit outcomeKey: Lens[Outcome, Key],
  outcomeArtifact: Lens[Outcome, Signed[Artifact]],
  outcomeContext: Lens[Outcome, Ctx],
  outcomeTrigger: Lens[Outcome, ConsensusTrigger]
) {

  import ctx.{advancer, logger => log, queue, remover, storage, updater}

  def checkUpdate(key: Key): F[Unit] =
    for {
      resources <- storage.getResources(key)
      maybeUpdate <- updater.tryUpdateConsensus(key, resources)
      _ <- maybeUpdate.traverse_ {
        case (_, newState) =>
          advancer
            .getConsensusOutcome(newState)
            .map { case (prevKey, outcome) => finalizeAndNotify(newState, prevKey, outcome) }
            .getOrElse(log.debug(s"State updated for key=$key"))
      }
    } yield ()

  private def finalizeAndNotify(
    newState: ConsensusState[Key, Status, Outcome, Kind],
    prevKey: Previous[Key],
    outcome: Outcome
  ): F[Unit] =
    for {
      now <- Async[F].monotonic
      _ <- Metrics[F].recordTime("dag_consensus_duration", now - newState.createdAt)

      updated <- storage.tryUpdateLastConsensusOutcomeWithCleanup(prevKey, outcome)
      _ <- ctx.nodeStorage.clearJoiningGracePeriod
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
      // Clear stale events that may have been received via gossip but were already processed
      // by other facilitators. Without this, hash intersection will be empty because we have
      // events that other nodes have already cleared from their mempools.
      _ <- ctx.advancer.onInitFromDownload
      outcome <- fetchOutcomeFromCluster(key, artifact, context)
        .flatMap(_.liftTo[F](new Throwable(s"[DownloadInit] Could not observe outcome for key=$key")))
      _ <- storage
        .trySetInitialConsensusOutcome(outcome)
        .ifM(
          ifFalse = new Throwable(s"[DownloadInit] Failed to initialize consensus storage").raiseError[F, Unit],
          ifTrue = ctx.nodeStorage.tryModifyState(NodeState.Observing, NodeState.WaitingForReady) >>
            ctx.nodeStorage.setJoiningGracePeriod >>
            queue.offer(StartRound(none))
        )
    } yield ()

  def initFromRollback(key: Key, outcome: Outcome): F[Unit] =
    for {
      _ <- log.info(s"[RollbackInit] Initializing consensus after rollback at key=$key")
      _ <- storage.trySetInitialConsensusOutcome(outcome)
      _ <- queue.offer(StartRound(TimeTrigger.some))
    } yield ()

  private def fetchOutcomeFromCluster(key: Key, artifact: Signed[Artifact], context: Ctx): F[Option[Outcome]] = {
    val retryPolicy = limitRetries(20).join(constantDelay(3.seconds))

    def selectPeer: F[Peer] =
      ctx.clusterStorage.getResponsivePeers.flatMap { allPeers =>
        val readyPeers = allPeers.filter(_.state == NodeState.Ready).toSeq
        val observingPeers = allPeers.filter(_.state == NodeState.Observing).toSeq

        val candidates = if (readyPeers.nonEmpty) readyPeers else observingPeers

        if (candidates.isEmpty) {
          val peerStates = allPeers.map(p => s"${p.id.show.take(8)}=${p.state}").mkString(", ")
          log.warn(s"[DownloadInit] No Ready/Observing peers available. Peer states: $peerStates") >>
            new NoValidPeersException(
              s"No peers in Ready or Observing state. Available: ${allPeers.size} peers"
            ).raiseError[F, Peer]
        } else {
          Random[F].elementOf(candidates)
        }
      }

    def fetch(peer: Peer): F[Option[Outcome]] =
      log.debug(s"[DownloadInit] Fetching outcome from peer ${peer.id.show.take(8)} (${peer.state})") >>
        ctx.consensusClient.getSpecificConsensusOutcome(GetConsensusOutcomeRequest(key)).run(peer)

    def wasSuccessful(maybeOutcome: Option[Outcome]): F[Boolean] =
      maybeOutcome.exists { outcome =>
        outcomeKey.get(outcome) === key &&
        outcomeArtifact.get(outcome) === artifact &&
        outcomeContext.get(outcome) === context
      }.pure[F]

    def onFailure(maybeOutcome: Option[Outcome], retryDetails: RetryDetails): F[Unit] =
      maybeOutcome.map { outcome =>
        val sameArtifact = outcomeArtifact.get(outcome) === artifact
        val sameContext = outcomeContext.get(outcome) === context
        log.info(
          s"Observed outcome {key=${key.show}, outcomeKey=${outcomeKey
              .get(outcome)}, sameArtifact=${sameArtifact.show}, sameContext=${sameContext.show}, attempt=${retryDetails.retriesSoFar}}"
        )
      }.getOrElse(log.info(s"Outcome not observed {key=${key.show}, attempt=${retryDetails.retriesSoFar}}"))

    def onError(err: Throwable, retryDetails: RetryDetails): F[Unit] =
      log.error(err)(s"Error when trying to observe consensus outcome {attempt=${retryDetails.retriesSoFar}}")

    (selectPeer >>= fetch).retryingOnFailuresAndAllErrors(
      wasSuccessful = wasSuccessful,
      policy = retryPolicy,
      onFailure = onFailure,
      onError = onError
    )
  }

  class NoValidPeersException(message: String) extends RuntimeException(message)
}
