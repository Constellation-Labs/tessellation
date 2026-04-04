package io.constellationnetwork.node.shared.infrastructure.consensus.state

import cats._
import cats.data.StateT
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
import io.constellationnetwork.node.shared.infrastructure.node.RestartService
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
        tryUpdateExistingConsensus(key, updateConsensus(resources))

      private def tryUpdateExistingConsensus(
        key: Key,
        fn: ConsensusState[Key, Status, Outcome, Kind] => F[(ConsensusState[Key, Status, Outcome, Kind], F[Unit])]
      ): F[StateUpdateResult] =
        consensusStorage
          .condModifyState(key)(toUpdateStateFn(fn))
          .flatMap(evalEffect)
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

      private def evalEffect(maybeResultAndEffect: Option[(StateUpdateResult, F[Unit])]): F[StateUpdateResult] =
        maybeResultAndEffect.flatTraverse { case (result, effect) => effect.as(result) }

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

  def recoverIfForking[F[_]: Async](
    ownObservationHash: Hash,
    observationName: String,
    restartService: RestartService[F, _],
    nodeStorage: NodeStorage[F],
    leavingDelay: FiniteDuration
  )(
    observations: SortedMap[PeerId, Hash]
  )(implicit metrics: Metrics[F]): F[Unit] =
    pickMajority(observations.values.toList).traverse { majorityObservationHash =>
      val isForked = majorityObservationHash =!= ownObservationHash

      if (isForked) {
        val majorityForkPeers = observations.collect {
          case (peerId, observationHash) if observationHash === majorityObservationHash => peerId
        }.toList

        val logger = Slf4jLogger.getLogger[F]

        metrics.incrementCounter(
          "dag_consensus_fork_detected",
          Seq(unsafeLabelName("observation_type") -> observationName)
        ) >>
          logger.warn(
            ConsensusLog.format(
              Category.Fork,
              "n/a",
              "n/a",
              LogEvent.ForkDetected,
              "observation" -> observationName,
              "majorityPeers" -> ConsensusLog.pids(majorityForkPeers),
              "totalObservations" -> observations.size.toString
            )
          ) >>
          // Transition to WaitingForDownload so DownloadDaemon triggers incremental
          // recovery from majority peers, instead of the old Leaving→Offline→restart
          // path which requires a full process restart and is too slow for tests.
          // Try from Ready first (normal case), then Observing (if already recovering).
          nodeStorage.setRecoveryDownload >>
          tryTransitionToDownload(nodeStorage, logger, observationName) >>
          ExitOnFork.exitOnFeature("CL_EXIT_ON_FORK")

      } else Applicative[F].unit
    }.void

  private def tryTransitionToDownload[F[_]: Async](
    nodeStorage: NodeStorage[F],
    logger: org.typelevel.log4cats.Logger[F],
    observationName: String
  )(implicit metrics: Metrics[F]): F[Unit] = {
    val candidateStates = List(
      NodeState.Ready,
      NodeState.Observing,
      NodeState.WaitingForReady
    )

    def tryStates(remaining: List[NodeState]): F[Option[NodeState]] =
      remaining match {
        case Nil => none[NodeState].pure[F]
        case state :: rest =>
          nodeStorage.tryModifyStateGetResult(Set(state), NodeState.WaitingForDownload).flatMap {
            case NodeStateTransition.Success => state.some.pure[F]
            case _                           => tryStates(rest)
          }
      }

    tryStates(candidateStates).flatMap {
      case Some(fromState) =>
        logger.warn(
          ConsensusLog.format(
            Category.Recovery,
            "n/a",
            "n/a",
            LogEvent.RecoveryStateTransition,
            "trigger" -> s"fork_detected_$observationName",
            "from" -> fromState.toString,
            "to" -> "WaitingForDownload"
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
            "trigger" -> s"fork_detected_$observationName",
            "action" -> "already_recovering"
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
