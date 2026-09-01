package io.constellationnetwork.node.shared.infrastructure.consensus.state

import cats._
import cats.data.StateT
import cats.effect.kernel.Ref
import cats.effect.{Async, Sync, Temporal}
import cats.syntax.all._

import scala.collection.immutable.SortedMap
import scala.concurrent.duration._
import scala.reflect.runtime.universe.TypeTag

import io.constellationnetwork.ext.collection.FoldableOps.pickMajority
import io.constellationnetwork.node.shared.domain.consensus.ConsensusFunctions
import io.constellationnetwork.node.shared.domain.node.NodeStorage
import io.constellationnetwork.node.shared.infrastructure.consensus.ConsensusLog.{Category, Event => LogEvent}
import io.constellationnetwork.node.shared.infrastructure.consensus.ConsensusStorage.ModifyStateFn
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.ConsensusTrigger
import io.constellationnetwork.node.shared.infrastructure.consensus.{ConsensusLog, _}
import io.constellationnetwork.node.shared.infrastructure.fork.ExitOnFork
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics.unsafeLabelName
import io.constellationnetwork.schema.node.{NodeState, NodeStateTransition}
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, SnapshotOrdinal}
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.{Hashed, Hasher}

import eu.timepit.refined.auto._
import io.circe.Encoder
import org.typelevel.log4cats.slf4j.Slf4jLogger

/** Updates consensus state based on received declarations.
  *
  * ==When Called==
  *
  * Called by StateTransitions.checkUpdate() after new data arrives via rumors.
  *
  * ==Update Pipeline==
  *
  * {{{
  *   tryUpdateConsensus(key, resources)
  *       │
  *       ├── updateFacilitators()     // Remove withdrawn peers
  *       └── advanceStatus()          // Try to move to next status
  *             │
  *             ▼
  *       Some((oldState, newState)) or None
  * }}}
  *
  * ==Key Methods==
  *
  * '''tryUpdateConsensus(key, resources):''' Main update method, returns old/new state if changed
  */
trait ConsensusStateUpdater[F[_], Key, Artifact, Context, Status, Outcome, Kind] {

  type StateUpdateResult = Option[(ConsensusState[Key, Status, Outcome, Kind], ConsensusState[Key, Status, Outcome, Kind])]

  def tryUpdateConsensus(key: Key, resources: ConsensusResources[Artifact, Kind]): F[StateUpdateResult]

}

object ConsensusStateUpdater {

  def make[F[
    _
  ]: Async: Metrics, Event, Key: Show: Order: TypeTag: Encoder, Artifact <: AnyRef, Context <: AnyRef, Status: Eq: Show, Outcome: Eq, Kind: Encoder: Eq: Show: TypeTag](
    consensusStateAdvancer: ConsensusStateAdvancer[F, Key, Artifact, Context, Status, Outcome, Kind],
    consensusStorage: ConsensusStorage[F, Event, Key, Artifact, Context, Status, Outcome, Kind],
    statusOps: ConsensusOps[Status, Kind]
  ): ConsensusStateUpdater[F, Key, Artifact, Context, Status, Outcome, Kind] =
    new ConsensusStateUpdater[F, Key, Artifact, Context, Status, Outcome, Kind] {

      private val logger = Slf4jLogger.getLoggerFromClass(ConsensusStateUpdater.getClass)

      def tryUpdateConsensus(key: Key, resources: ConsensusResources[Artifact, Kind]): F[StateUpdateResult] =
        consensusStorage.resumePendingStateEffect(key) >>
          tryUpdateExistingConsensus(key, updateConsensus(resources))

      private def tryUpdateExistingConsensus(
        key: Key,
        fn: ConsensusState[Key, Status, Outcome, Kind] => F[(ConsensusState[Key, Status, Outcome, Kind], F[Unit])]
      ): F[StateUpdateResult] =
        consensusStorage
          .condModifyStateWithSideEffect(key)(toUpdateStateFn(fn))
          .map(_.flatten)
          .flatTap(logIfUpdatedState)

      private def toUpdateStateFn(
        fn: ConsensusState[Key, Status, Outcome, Kind] => F[(ConsensusState[Key, Status, Outcome, Kind], F[Unit])]
      ): ModifyStateFn[F, Key, Status, Outcome, Kind, (StateUpdateResult, F[Unit])] = { maybeState =>
        maybeState.flatTraverse { oldState =>
          fn(oldState).map {
            case (newState, effect) =>
              Option.when(newState =!= oldState)((newState.some, ((oldState, newState).some, effect)))
          }
        }
      }

      private def logIfUpdatedState(updateResult: StateUpdateResult): F[Unit] =
        updateResult.traverse {
          case (oldState, newState) =>
            val oldStatusName = oldState.status.getClass.getSimpleName.stripSuffix("$")
            val newStatusName = newState.status.getClass.getSimpleName.stripSuffix("$")
            val statusTransition = if (oldStatusName =!= newStatusName) s"$oldStatusName→$newStatusName" else newStatusName
            ConsensusLog.info(
              logger,
              Category.Phase,
              newState.key.show,
              "n/a",
              LogEvent.StateUpdated,
              "status" -> statusTransition,
              "facilitators" -> newState.facilitators.value.size.toString,
              "leader" -> ConsensusLog.pid(newState.leader),
              "view" -> newState.viewNumber.toString
            )
        }.void

      private def updateConsensus(resources: ConsensusResources[Artifact, Kind])(
        state: ConsensusState[Key, Status, Outcome, Kind]
      ): F[(ConsensusState[Key, Status, Outcome, Kind], F[Unit])] = {
        val stateAndEffect = for {
          _ <- updateFacilitators(resources)
          effect <- consensusStateAdvancer.advanceStatus(resources)
        } yield effect

        stateAndEffect
          .run(state)
      }

      private def updateFacilitators(
        resources: ConsensusResources[Artifact, Kind]
      ): StateT[F, ConsensusState[Key, Status, Outcome, Kind], Unit] =
        StateT { state =>
          val newState: ConsensusState[Key, Status, Outcome, Kind] =
            if (resources.withdrawalsMap.isEmpty)
              state
            else
              statusOps
                .maybeCollectingKind(state.status)
                .map { collectingKind =>
                  val (withdrawn, remained) = state.facilitators.value.partition { peerId =>
                    resources.withdrawalsMap.get(peerId).contains(collectingKind)
                  }
                  state.copy(
                    facilitators = Facilitators(remained),
                    withdrawnFacilitators = WithdrawnFacilitators(state.withdrawnFacilitators.value.union(withdrawn.toSet))
                  ): ConsensusState[Key, Status, Outcome, Kind]
                }
                .getOrElse(state)

          val withdrawnCount = newState.withdrawnFacilitators.value.size - state.withdrawnFacilitators.value.size
          val effect =
            if (withdrawnCount > 0)
              Metrics[F].incrementCounterBy("dag_consensus_facilitator_withdrawal", withdrawnCount)
            else Applicative[F].unit

          effect.as((newState, ()))
        }
    }

  /** Compute a strict-majority hash (count > observations / 2). Returns None when no value reaches strict majority — e.g. a 2/2/1 split or
    * a single-element sample is treated as unanimous only when sole value -> majority by definition. The general guarantee callers care
    * about is "more than half of the observed peers agree on this hash."
    *
    * Replaces `FoldableOps.pickMajority` (plurality) at the fork-detection seam, where treating a plurality as authoritative could
    * seed/confirm suspicion against a non-majority cohort.
    */
  private[consensus] def strictMajorityHash(observations: Iterable[Hash]): Option[Hash] = {
    val total = observations.size
    if (total === 0) None
    else {
      val (winnerHash, winnerCount) = observations.groupBy(identity).view.mapValues(_.size).maxBy(_._2)
      if (winnerCount * 2 > total) winnerHash.some else None
    }
  }

  /** Detect a sustained-divergence fork and trigger recovery if confirmed.
    *
    * Two-stage gate to avoid the simultaneous-recovery cascade observed in the alpha.40 testnet incident: a single observation no longer
    * flips the node into `WaitingForDownload`. Instead the first divergent strict-majority is RECORDED in `forkObservationsRef` with a
    * monotonic timestamp, and a subsequent call with the same divergent majority can trigger recovery only if the divergence has persisted
    * for at least `confirmationWindow`. Local-only timing decision; cluster safety is unaffected.
    *
    * Behaviour:
    *   - `observations.size < minObservations` -> log "insufficient sample" and do not update the tracker. Pass `minObservations = 1` at
    *     authoritative-single-source sites (e.g. proposal-phase leader-vs-self check), `2+` at polled-majority sites (Facility-phase
    *     facilitators-hash sweep).
    *   - no STRICT majority (>50%) -> log "no_strict_majority" and do not update the tracker (avoids treating a 2/2/1 split as
    *     authoritative).
    *   - strict majority matches own hash -> clear the tracker entry for `observationName` (drop a stale suspicion), no recovery
    *   - strict majority differs from own hash:
    *     - no prior entry, or prior entry's hash differs from current majority -> record (majorityHash, now), log "suspected fork, awaiting
    *       confirmation"
    *     - prior entry matches current majority and (now - firstSeenAt) >= confirmationWindow -> log FORK_DETECTED + flip
    *       Ready/Observing/WaitingForReady -> WaitingForDownload + clear tracker
    *     - prior entry matches but window not elapsed -> log "awaiting confirmation, elapsed=Xs required=Ys", no recovery
    *
    * `confirmationWindow = 0` disables the gate (legacy single-sample behaviour, retained for single-peer/genesis topologies and tests that
    * rely on immediate recovery).
    *
    * NOTE: confirmation only fires on a SUBSEQUENT call to this function. If declarations stop arriving after the first divergent sample,
    * the suspicion lingers until the next observation. This is event-driven, not background-timer-driven; in practice the consensus phases
    * that call `recoverIfForking` retransmit on a cadence shorter than `confirmationWindow`, so confirmation does fire promptly when the
    * divergence is real.
    *
    * NOTE: this function MUST NOT be called for divergence classes that recovery cannot resolve (e.g. `consensusConfigHash` mismatch — a
    * config divergence persists across downloads). Those should log + alert instead. See `logRecoveryUnsuitableMismatch`.
    */
  def recoverIfForking[F[_]: Async](
    ownObservationHash: Hash,
    observation: ForkObservation,
    nodeStorage: NodeStorage[F],
    forkObservationsRef: Ref[F, Map[ForkObservation, (Hash, FiniteDuration)]],
    confirmationWindow: FiniteDuration,
    minObservations: Int
  )(
    observations: SortedMap[PeerId, Hash]
  )(implicit metrics: Metrics[F]): F[Unit] = {
    val logger = Slf4jLogger.getLogger[F]
    val totalObservations = observations.size

    strictMajorityHash(observations.values) match {
      case None if totalObservations > 0 =>
        // Aug 11, ordinal 5,881,764: this exact facilitators-hash split was visible immediately before two nodes finalized the same artifact
        // under different local outcome metadata. There is no authoritative cohort to recover toward yet, so do not mutate node state, but
        // escalate the formerly INFO-only observation to an alertable metric + WARN rather than silently continuing.
        metrics.incrementCounter(
          "dag_consensus_fork_ambiguous_total",
          Seq(unsafeLabelName("observation_type") -> observation.label)
        ) >>
          logger.warn(
            ConsensusLog.format(
              Category.Fork,
              "n/a",
              "n/a",
              LogEvent.ForkDetected,
              "observation" -> observation.label,
              "totalObservations" -> totalObservations.toString,
              "action" -> ForkAction.NoStrictMajority.label,
              "severity" -> "critical"
            )
          )
      case None => Applicative[F].unit
      case Some(majorityObservationHash) =>
        recoverIfForkingWithMajority(
          ownObservationHash,
          observation,
          nodeStorage,
          forkObservationsRef,
          confirmationWindow,
          minObservations,
          observations,
          majorityObservationHash,
          totalObservations,
          logger
        )
    }
  }

  private def recoverIfForkingWithMajority[F[_]: Async](
    ownObservationHash: Hash,
    observation: ForkObservation,
    nodeStorage: NodeStorage[F],
    forkObservationsRef: Ref[F, Map[ForkObservation, (Hash, FiniteDuration)]],
    confirmationWindow: FiniteDuration,
    minObservations: Int,
    observations: SortedMap[PeerId, Hash],
    majorityObservationHash: Hash,
    totalObservations: Int,
    logger: org.typelevel.log4cats.Logger[F]
  )(implicit metrics: Metrics[F]): F[Unit] = {
    val isForked = majorityObservationHash =!= ownObservationHash
    val majorityForkPeers = observations.collect {
      case (peerId, observationHash) if observationHash === majorityObservationHash => peerId
    }.toList

    if (!isForked) {
      // Local node matches the strict majority — clear any pending suspicion for this observation type.
      forkObservationsRef.update(_ - observation)
    } else if (totalObservations < minObservations) {
      logger.info(
        ConsensusLog.format(
          Category.Fork,
          "n/a",
          "n/a",
          LogEvent.ForkDetected,
          "observation" -> observation.label,
          "totalObservations" -> totalObservations.toString,
          "minObservations" -> minObservations.toString,
          "action" -> ForkAction.IgnoredInsufficientSample.label
        )
      )
    } else if (confirmationWindow <= 0.millis) {
      // Legacy single-sample behaviour: trigger recovery immediately.
      triggerRecovery(observation, majorityForkPeers, totalObservations, nodeStorage, logger) >>
        forkObservationsRef.update(_ - observation)
    } else {
      for {
        now <- Async[F].monotonic
        decision <- forkObservationsRef.modify { current =>
          current.get(observation) match {
            case Some((existingHash, firstSeenAt))
                if existingHash === majorityObservationHash && (now - firstSeenAt) >= confirmationWindow =>
              (current - observation, ForkDecision.Confirm(now - firstSeenAt))
            case Some((existingHash, firstSeenAt)) if existingHash === majorityObservationHash =>
              (current, ForkDecision.AwaitWindow(now - firstSeenAt))
            case _ =>
              (current.updated(observation, (majorityObservationHash, now)), ForkDecision.Record)
          }
        }
        _ <- decision match {
          case ForkDecision.Confirm(elapsed) =>
            triggerRecovery(observation, majorityForkPeers, totalObservations, nodeStorage, logger, elapsed.some)
          case ForkDecision.AwaitWindow(elapsed) =>
            logger.info(
              ConsensusLog.format(
                Category.Fork,
                "n/a",
                "n/a",
                LogEvent.ForkDetected,
                "observation" -> observation.label,
                "majorityPeers" -> ConsensusLog.pids(majorityForkPeers),
                "totalObservations" -> totalObservations.toString,
                "elapsedMs" -> elapsed.toMillis.toString,
                "windowMs" -> confirmationWindow.toMillis.toString,
                "action" -> ForkAction.AwaitingConfirmation.label
              )
            )
          case ForkDecision.Record =>
            logger.info(
              ConsensusLog.format(
                Category.Fork,
                "n/a",
                "n/a",
                LogEvent.ForkDetected,
                "observation" -> observation.label,
                "majorityPeers" -> ConsensusLog.pids(majorityForkPeers),
                "totalObservations" -> totalObservations.toString,
                "windowMs" -> confirmationWindow.toMillis.toString,
                "action" -> ForkAction.SuspicionRecorded.label
              )
            )
        }
      } yield ()
    }
  }

  private sealed trait ForkDecision
  private object ForkDecision {
    case object Record extends ForkDecision
    final case class AwaitWindow(elapsed: FiniteDuration) extends ForkDecision
    final case class Confirm(elapsed: FiniteDuration) extends ForkDecision
  }

  /** Identifies which divergence type a fork-detection sample is sourced from. The `label` is the stable string used as a Prometheus label
    * value and a log field — keep it grep-stable. Adding a new observation type requires adding a case here and verifying the operator's
    * dashboards include the new label.
    */
  sealed abstract class ForkObservation(val label: String)
  object ForkObservation {
    case object LastSnapshotHash extends ForkObservation("last-snapshot-hash")
    case object FacilitatorsHash extends ForkObservation("facilitators-hash")
    case object ConsensusConfigHash extends ForkObservation("consensus-config-hash")
  }

  /** What `recoverIfForking` decided to do for a given sample. The `label` is the stable structured-log `action` field — keep it
    * grep-stable; operator dashboards/alerts pivot on these values.
    */
  sealed abstract class ForkAction(val label: String)
  object ForkAction {
    case object NoStrictMajority extends ForkAction("no_strict_majority")
    case object IgnoredInsufficientSample extends ForkAction("ignored_insufficient_sample")
    case object AwaitingConfirmation extends ForkAction("awaiting_confirmation")
    case object SuspicionRecorded extends ForkAction("suspicion_recorded")
    case object LoggedNoRecovery extends ForkAction("logged_no_recovery")
    case object ConfirmedRecovery extends ForkAction("confirmed_recovery")
  }

  /** Log + metric a divergence class that cannot be repaired by recovery download.
    *
    * For example, a `consensusConfigHash` mismatch indicates peers running with different config; a recovery download cycle won't change
    * the local config, so re-triggering recovery loops without progress. Instead we surface the misconfiguration via a dedicated counter
    * and structured log so operators can intervene.
    *
    * Prevents `consensusConfigHash` from re-entering recovery on every round.
    */
  def logRecoveryUnsuitableMismatch[F[_]: Sync](
    ownObservationHash: Hash,
    observation: ForkObservation
  )(
    observations: SortedMap[PeerId, Hash]
  )(implicit metrics: Metrics[F]): F[Unit] = {
    val logger = Slf4jLogger.getLogger[F]
    val divergent = observations.collect { case (pid, h) if h =!= ownObservationHash => pid }.toList
    if (divergent.isEmpty) Applicative[F].unit
    else
      metrics.incrementCounter(
        "dag_consensus_unrepairable_mismatch",
        Seq(unsafeLabelName("observation_type") -> observation.label)
      ) >>
        logger.warn(
          ConsensusLog.format(
            Category.Fork,
            "n/a",
            "n/a",
            LogEvent.ForkDetected,
            "observation" -> observation.label,
            "divergentPeers" -> ConsensusLog.pids(divergent),
            "totalObservations" -> observations.size.toString,
            "action" -> ForkAction.LoggedNoRecovery.label
          )
        )
  }

  private def triggerRecovery[F[_]: Async](
    observation: ForkObservation,
    majorityForkPeers: List[PeerId],
    totalObservations: Int,
    nodeStorage: NodeStorage[F],
    logger: org.typelevel.log4cats.Logger[F],
    elapsedConfirmation: Option[FiniteDuration] = None
  )(implicit metrics: Metrics[F]): F[Unit] =
    metrics.incrementCounter(
      "dag_consensus_fork_detected",
      Seq(unsafeLabelName("observation_type") -> observation.label)
    ) >>
      logger.warn(
        ConsensusLog.format(
          Category.Fork,
          "n/a",
          "n/a",
          LogEvent.ForkDetected,
          (Seq(
            "observation" -> observation.label,
            "majorityPeers" -> ConsensusLog.pids(majorityForkPeers),
            "totalObservations" -> totalObservations.toString,
            "action" -> ForkAction.ConfirmedRecovery.label
          ) ++ elapsedConfirmation.map(e => "confirmedAfterMs" -> e.toMillis.toString).toSeq): _*
        )
      ) >>
      // Transition to WaitingForDownload so DownloadDaemon triggers incremental
      // recovery from majority peers, instead of the old Leaving->Offline->restart
      // path which requires a full process restart and is too slow for tests.
      // Try from Ready first (normal case), then Observing (if already recovering).
      nodeStorage.setRecoveryDownload >>
      tryTransitionToDownload(nodeStorage, logger, observation) >>
      ExitOnFork.exitOnFeature("CL_EXIT_ON_FORK")

  private def tryTransitionToDownload[F[_]: Async](
    nodeStorage: NodeStorage[F],
    logger: org.typelevel.log4cats.Logger[F],
    observation: ForkObservation
  )(implicit metrics: Metrics[F]): F[Unit] = {
    val candidateStates = List(
      NodeState.Ready,
      NodeState.Observing,
      NodeState.WaitingForReady
    )

    candidateStates.collectFirstSomeM { state =>
      nodeStorage
        .tryModifyStateGetResult(Set[NodeState](state), NodeState.WaitingForDownload)
        .map {
          case NodeStateTransition.Success => state.some
          case _                           => none[NodeState]
        }
    }.flatMap {
      case Some(fromState) =>
        logger.warn(
          ConsensusLog.format(
            Category.Recovery,
            "n/a",
            "n/a",
            LogEvent.RecoveryStateTransition,
            "trigger" -> s"fork_detected_${observation.label}",
            "from" -> fromState.toString,
            "to" -> "WaitingForDownload"
          )
        ) >>
          metrics.incrementCounter(
            "dag_consensus_recovery_state_transition_total",
            Seq(
              unsafeLabelName("trigger") -> s"fork_detected_${observation.label}",
              unsafeLabelName("trigger_class") -> "fork_detected",
              unsafeLabelName("outcome") -> "transitioned"
            )
          )
      case None =>
        // Already in WaitingForDownload, DownloadInProgress, or other recovery state.
        // DownloadDaemon is already handling it.
        logger.info(
          ConsensusLog.format(
            Category.Recovery,
            "n/a",
            "n/a",
            LogEvent.RecoveryStateTransition,
            "trigger" -> s"fork_detected_${observation.label}",
            "action" -> "already_recovering"
          )
        ) >>
          metrics.incrementCounter(
            "dag_consensus_recovery_state_transition_total",
            Seq(
              unsafeLabelName("trigger") -> s"fork_detected_${observation.label}",
              unsafeLabelName("trigger_class") -> "fork_detected",
              unsafeLabelName("outcome") -> "already_recovering"
            )
          )
    }
  }

  /** Identify peers whose observation hash differs from the local node's (i.e., forked peers).
    *
    * When this node is in the majority (its hash matches the majority hash), returns all peers with a different hash. When this node is in
    * the minority or there's no clear majority, returns empty set — `recoverIfForking` handles the minority self-recovery case.
    *
    * This is deterministic: all healthy nodes have the same `ownObservationHash` and see the same `observations`, so they all identify the
    * same set of forked peers.
    */
  def identifyForkedPeers(
    ownObservationHash: Hash,
    observations: SortedMap[PeerId, Hash]
  ): Set[PeerId] =
    pickMajority(observations.values.toList) match {
      case Some(majorityHash) if majorityHash === ownObservationHash =>
        observations.collect { case (pid, hash) if hash =!= ownObservationHash => pid }.toSet
      case _ =>
        Set.empty[PeerId]
    }

  def pickValidatedMajorityArtifact[F[_]: Sync, Event, Key, Artifact, Context, Kind](
    ownProposalInfo: ArtifactInfo[Artifact, Context],
    lastSignedArtifact: Signed[Artifact],
    lastContext: Context,
    trigger: ConsensusTrigger,
    resources: ConsensusResources[Artifact, Kind],
    proposals: List[Hash],
    facilitators: Set[PeerId],
    consensusFns: ConsensusFunctions[F, Event, Key, Artifact, Context],
    getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]]
  )(implicit hasher: Hasher[F], metrics: Metrics[F]): F[Option[ArtifactInfo[Artifact, Context]]] = {
    val totalProposals = proposals.size
    val majorityThreshold = totalProposals / 2

    def go(proposals: List[(Int, Hash)], isFirst: Boolean): F[Option[ArtifactInfo[Artifact, Context]]] =
      proposals match {
        case (occurrences, majorityHash) :: tail =>
          if (majorityHash === ownProposalInfo.hash)
            ownProposalInfo.some.pure[F]
          else
            resources.artifacts
              .get(majorityHash)
              .traverse { artifact =>
                consensusFns
                  .validateArtifact(
                    lastSignedArtifact,
                    lastContext,
                    trigger,
                    artifact,
                    facilitators,
                    getGlobalSnapshotByOrdinal
                  )
                  .map { validationResultOrError =>
                    validationResultOrError.map {
                      case (artifact, context) =>
                        ArtifactInfo(artifact, context, majorityHash)
                    }
                  }
              }
              .flatMap { maybeArtifactInfoOrErr =>
                maybeArtifactInfoOrErr.flatTraverse { artifactInfoOrErr =>
                  artifactInfoOrErr.fold(
                    cause =>
                      if (isFirst && occurrences > majorityThreshold)
                        // The clear majority hash failed validation on this node.
                        // Falling back to a less popular hash would diverge from the majority of nodes.
                        // Abandon the round instead — the stall detector will recover.
                        metrics.incrementCounter("dag_consensus_majority_artifact_abandoned") >>
                          Slf4jLogger
                            .getLogger[F]
                            .error(cause)(
                              ConsensusLog.format(
                                Category.Validation,
                                "n/a",
                                "n/a",
                                LogEvent.MajorityArtifactAbandoned,
                                "hash" -> majorityHash.show.take(8),
                                "proposals" -> s"$occurrences/$totalProposals",
                                "reason" -> "validation_failed"
                              )
                            ) >> none[ArtifactInfo[Artifact, Context]].pure[F]
                      else
                        metrics.incrementCounter("dag_consensus_majority_artifact_fallback") >>
                          Slf4jLogger
                            .getLogger[F]
                            .warn(cause)(
                              ConsensusLog.format(
                                Category.Validation,
                                "n/a",
                                "n/a",
                                LogEvent.MajorityArtifactFallback,
                                "hash" -> majorityHash.show.take(8),
                                "occurrences" -> occurrences.toString
                              )
                            ) >>
                          go(tail, isFirst = false),
                    ai => ai.some.pure[F]
                  )
                }
              }
        case Nil => none[ArtifactInfo[Artifact, Context]].pure[F]
      }

    val sortedProposals = proposals.foldMap(a => Map(a -> 1)).toList.map(_.swap).sorted.reverse
    go(sortedProposals, isFirst = true)
  }

  def proposalAffinity[A: Order](proposals: List[A], proposal: A): Double =
    if (proposals.nonEmpty)
      proposals.count(Order[A].eqv(proposal, _)).toDouble / proposals.size.toDouble
    else
      0.0

}
