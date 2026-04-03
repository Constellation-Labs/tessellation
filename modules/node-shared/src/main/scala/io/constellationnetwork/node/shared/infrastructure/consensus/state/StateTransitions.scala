package io.constellationnetwork.node.shared.infrastructure.consensus.state

import cats.effect.kernel.{Async, Temporal}
import cats.effect.std.Random
import cats.syntax.all._
import cats.{Eq, Show}

import scala.concurrent.duration._

import io.constellationnetwork.node.shared.infrastructure.consensus.ConsensusLog
import io.constellationnetwork.node.shared.infrastructure.consensus.ConsensusLog.{Category, Event => LogEvent}
import io.constellationnetwork.node.shared.infrastructure.consensus.engine.ConsensusCommand._
import io.constellationnetwork.node.shared.infrastructure.consensus.message.GetConsensusOutcomeRequest
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger._
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics.unsafeLabelName
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
            .getOrElse(log.debug(ConsensusLog.format(Category.Phase, key.show, "n/a", LogEvent.StateUpdated)))
      }
    } yield ()

  private def finalizeAndNotify(
    newState: ConsensusState[Key, Status, Outcome, Kind],
    prevKey: Previous[Key],
    outcome: Outcome
  ): F[Unit] =
    for {
      now <- Async[F].monotonic
      duration = now - newState.createdAt
      _ <- Metrics[F].recordTime("dag_consensus_duration", duration)
      _ <- Metrics[F].recordTimeHistogram("dag_consensus_duration", duration)

      _ <- ctx.peerQualityTracker.recordRoundSuccess(newState.facilitators.value.toSet)
      leaderScore <- ctx.peerQualityTracker.getQualityScore(newState.leader)
      updated <- storage.tryUpdateLastConsensusOutcomeWithCleanup(prevKey, outcome)
      _ <- ctx.nodeStorage.decrementJoiningGracePeriod
      // Prune stale resources for keys other than the newly completed key.
      // This prevents memory growth from abandoned rounds leaving behind resource entries.
      activeKey = outcomeKey.get(outcome)
      _ <- storage.pruneStaleResources(activeKey)
      // Prune peer registrations from peers no longer in the cluster.
      // Peer registrations must be pruned to prevent stale departed-peer entries from
      // corrupting lagging detection in StallDetector (peersAtDifferentKey count).
      responsivePeers <- ctx.clusterStorage.getResponsivePeers
      activePeerIds = responsivePeers.map(_.id) + ctx.selfId
      _ <- storage.pruneStalePeerRegistrations(activePeerIds)
      _ <-
        if (updated) {
          val key = activeKey
          val trigger = outcomeTrigger.get(outcome)

          val withdrawnCount = newState.withdrawnFacilitators.value.size
          val removedCount = newState.removedFacilitators.value.size

          Metrics[F].incrementCounter(
            "dag_consensus_outcome_finalized",
            Seq(unsafeLabelName("trigger_type") -> trigger.toString)
          ) >>
            Metrics[F].updateGauge("dag_consensus_round_facilitator_count", newState.facilitators.value.size) >>
            Metrics[F].updateGauge("dag_consensus_round_eligible_count", newState.eligibleFacilitators.value.size) >>
            ConsensusLog.info(
              log,
              Category.Lifecycle,
              key.show,
              ConsensusLog.role(ctx.selfId, newState.leader),
              LogEvent.RoundCompleted,
              (Seq(
                "trigger" -> trigger.toString,
                "duration" -> s"${duration.toMillis}ms",
                "facilitators" -> newState.facilitators.value.size.toString,
                "leader" -> ConsensusLog.pid(newState.leader),
                "leaderScore" -> f"$leaderScore%.2f",
                "view" -> newState.viewNumber.toString
              ) ++
                (if (withdrawnCount > 0) Seq("withdrawn" -> withdrawnCount.toString) else Seq.empty) ++
                (if (removedCount > 0) Seq("removed" -> removedCount.toString) else Seq.empty)): _*
            ) >>
            ctx.nodeStorage.tryModifyStateGetResult(NodeState.WaitingForReady, NodeState.Ready).void >>
            queue.offer(ConsensusFinished(key, outcome, trigger))
        } else {
          // OUTCOME_CONFLICT: another round completed first and stored its outcome.
          // Clean up the stale state and resources for this key to prevent memory leaks.
          // Without this cleanup, finished state entries accumulate in statesR/resourcesR
          // since cleanupStateAndResource only runs on the success path.
          storage.cleanupConflictedRound(activeKey) >>
            Metrics[F].incrementCounter("dag_consensus_outcome_conflict") >>
            ConsensusLog.warn(
              log,
              Category.Lifecycle,
              activeKey.show,
              "n/a",
              LogEvent.OutcomeConflict,
              "reason" -> "concurrent_finalization"
            )
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

  def initFromDownload(key: Key, artifact: Signed[Artifact], context: Ctx, isRecovery: Boolean = false): F[Unit] =
    for {
      _ <- ConsensusLog.info(log, Category.Lifecycle, key.toString, "n/a", LogEvent.DownloadInitStart)
      // isRecoveryEffective = true if either the caller flagged this as recovery, OR the cluster
      // has advanced past our downloaded ordinal (peer returned a newer outcome). In both cases
      // we skip the 43s TimeTrigger deferral so the node joins the cluster immediately.
      (outcome, isRecoveryEffective) <- fetchOutcomeFromCluster(key, artifact, context)
        .flatMap(_.liftTo[F](new Throwable(s"[DownloadInit] Could not observe outcome for key=$key")))
        .flatMap { o =>
          // Explicit post-retry validation: retryingOnFailuresAndAllErrors returns the last value
          // when retries exhaust, even if wasSuccessful returned false for it. This guard prevents
          // silently accepting a mismatched outcome (wrong artifact/context) into consensus storage.
          val keyMatch = outcomeKey.get(o) === key
          val artifactMatch = outcomeArtifact.get(o) === artifact
          val contextMatch = outcomeContext.get(o) === context
          if (keyMatch && artifactMatch && contextMatch) (o, isRecovery).pure[F]
          else {
            // If the peer returned a DIFFERENT outcome (cluster has moved on past our downloaded
            // ordinal), accept it and treat as recovery — skip the 43s deferral so we join
            // the cluster at its current tip instead of targeting a stale ordinal.
            val keyMismatch = outcomeKey.get(o) =!= key
            if (keyMismatch)
              (o, true).pure[F]
            else
              new Throwable(
                s"[DownloadInit] Outcome validation failed after retries for key=$key: " +
                  s"keyMatch=$keyMatch, artifactMatch=$artifactMatch, contextMatch=$contextMatch"
              ).raiseError[F, (Outcome, Boolean)]
          }
        }
      _ <- storage
        .trySetInitialConsensusOutcome(outcome)
        .ifM(
          ifFalse = new Throwable(s"[DownloadInit] Failed to initialize consensus storage").raiseError[F, Unit],
          ifTrue = ctx.nodeStorage.tryModifyState(NodeState.Observing, NodeState.WaitingForReady) >>
            ctx.nodeStorage.setJoiningGracePeriod >> {
              if (isRecoveryEffective) {
                // Recovery: skip TimeTrigger deferral. The cluster is already running and the
                // recovered node needs to join the next round immediately. Deferring 43s would
                // cause the cluster to advance further, making the node's first round stale.
                ConsensusLog.info(
                  log,
                  Category.Lifecycle,
                  key.toString,
                  "n/a",
                  LogEvent.DownloadInitRecoveryImmediate,
                  "note" -> "Skipping deferral for recovery download"
                ) >>
                  queue.offer(StartRound(TimeTrigger.some))
              } else {
                // Initial join: Defer first round to align with the cluster's TimeTrigger cadence.
                // Without this delay, validators fire StartRound immediately after download while
                // genesis is still mid-cycle on its 43s TimeTrigger. With N=4, the 3 validators
                // form a 75% majority and chain ahead without genesis, causing an irrecoverable
                // ordinal split (validators on N+2, genesis stuck on N+1 with facilitators=4).
                // Sleeping for timeTriggerInterval synchronizes the validator's first round with
                // the cluster's existing cadence, ensuring all nodes participate together.
                ConsensusLog.info(
                  log,
                  Category.Lifecycle,
                  key.toString,
                  "n/a",
                  LogEvent.DownloadInitDeferred,
                  "deferral" -> s"${ctx.config.timeTriggerInterval.toSeconds}s"
                ) >>
                  Temporal[F].sleep(ctx.config.timeTriggerInterval) >>
                  queue.offer(StartRound(TimeTrigger.some))
              }
            }
        )
    } yield ()

  def initFromRollback(key: Key, outcome: Outcome): F[Unit] =
    for {
      _ <- ConsensusLog.info(log, Category.Lifecycle, key.toString, "n/a", LogEvent.RollbackInitStart)
      // Clear ALL stale consensus state before initializing from rollback.
      // Without this cleanup, peer registrations from the pre-rollback network survive
      // and contain keys higher than the rollback ordinal. The StallDetector then sees
      // the rollback node as "lagging behind network" (peersAtHigherKey > total/2) and
      // immediately abandons rounds → recovery download → 0 selectable peers → stuck.
      // This mirrors the cleanup done in AbandonmentTracker.attemptRecoveryDownload.
      _ <- storage.clearAllConsensusState
      _ <- storage.clearAllPeerRegistrations
      _ <- storage.clearTimeTrigger
      _ <- storage.clearObservationKey
      _ <- ctx.pending.clear()
      _ <- ConsensusLog.info(log, Category.Lifecycle, key.toString, "n/a", LogEvent.RollbackStateCleared)
      _ <- storage.trySetInitialConsensusOutcome(outcome)
      // Set joining grace period to use relaxed timeouts for first rounds after rollback.
      // Without this, the rollback node uses aggressive timeouts while peers are still
      // downloading to the rollback ordinal, leading to premature stall detection.
      _ <- ctx.nodeStorage.setJoiningGracePeriod
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
          val peerStates = allPeers.toList.map(p => s"${ConsensusLog.pid(p.id)}=${p.state}").mkString(", ")
          ConsensusLog.warn(
            log,
            Category.Lifecycle,
            "n/a",
            "n/a",
            LogEvent.DownloadInitNoPeers,
            "peerStates" -> s"[$peerStates]"
          ) >>
            new NoValidPeersException(
              s"No peers in Ready or Observing state. Available: ${allPeers.size} peers"
            ).raiseError[F, Peer]
        } else {
          Random[F].elementOf(candidates)
        }
      }

    def fetch(peer: Peer): F[Option[Outcome]] =
      ConsensusLog.debug(
        log,
        Category.Lifecycle,
        key.toString,
        "n/a",
        LogEvent.DownloadInitFetch,
        "peer" -> ConsensusLog.pid(peer.id),
        "state" -> peer.state.toString
      ) >>
        ctx.consensusClient
          .getSpecificConsensusOutcome(GetConsensusOutcomeRequest(key))
          .run(peer)
          .recoverWith {
            // 409 means the peer has already evicted this ordinal's outcome (cluster moved on).
            // Fall back to the latest available outcome so we can join at the current tip.
            case _: org.http4s.client.UnexpectedStatus =>
              ctx.consensusClient.getLatestConsensusOutcome.run(peer)
          }

    def wasSuccessful(maybeOutcome: Option[Outcome]): F[Boolean] =
      maybeOutcome.exists { outcome =>
        outcomeKey.get(outcome) === key &&
        outcomeArtifact.get(outcome) === artifact &&
        outcomeContext.get(outcome) === context
      }.pure[F]

    def onFailure(maybeOutcome: Option[Outcome], retryDetails: RetryDetails): F[Unit] = {
      val attempt = retryDetails.retriesSoFar
      // Reduce noise: log every 5th attempt and the last attempt to avoid 20 nearly-identical lines
      if (attempt % 5 == 0 || attempt >= 19) {
        maybeOutcome.map { outcome =>
          val sameArtifact = outcomeArtifact.get(outcome) === artifact
          val sameContext = outcomeContext.get(outcome) === context
          ConsensusLog.info(
            log,
            Category.Lifecycle,
            key.show,
            "n/a",
            LogEvent.DownloadInitMismatch,
            "sameArtifact" -> sameArtifact.show,
            "sameContext" -> sameContext.show,
            "attempt" -> attempt.toString
          )
        }.getOrElse(
          ConsensusLog.info(
            log,
            Category.Lifecycle,
            key.show,
            "n/a",
            LogEvent.DownloadInitWaiting,
            "attempt" -> attempt.toString
          )
        )
      } else Async[F].unit
    }

    def onError(err: Throwable, retryDetails: RetryDetails): F[Unit] =
      log.error(err)(
        ConsensusLog.format(
          Category.Lifecycle,
          key.show,
          "n/a",
          LogEvent.DownloadInitError,
          "attempt" -> retryDetails.retriesSoFar.toString,
          "error" -> err.getMessage
        )
      )

    (selectPeer >>= fetch).retryingOnFailuresAndAllErrors(
      wasSuccessful = wasSuccessful,
      policy = retryPolicy,
      onFailure = onFailure,
      onError = onError
    )
  }

  class NoValidPeersException(message: String) extends RuntimeException(message)
}
