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
import io.constellationnetwork.node.shared.domain.gossip.Gossip
import io.constellationnetwork.node.shared.domain.node.NodeStorage
import io.constellationnetwork.node.shared.infrastructure.consensus.ConsensusStorage.ModifyStateFn
import io.constellationnetwork.node.shared.infrastructure.consensus._
import io.constellationnetwork.node.shared.infrastructure.consensus.message._
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.ConsensusTrigger
import io.constellationnetwork.node.shared.infrastructure.consensus.update.UnlockConsensusUpdate
import io.constellationnetwork.node.shared.infrastructure.fork.ExitOnFork
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics.unsafeLabelName
import io.constellationnetwork.node.shared.infrastructure.node.RestartService
import io.constellationnetwork.schema.node.NodeState
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
  *       ├── unlockConsensusFn()      // Check if we can unlock
  *       ├── updateFacilitators()     // Remove withdrawn peers
  *       ├── spreadHistoricalAck()    // Spread acks we haven't spread
  *       └── advanceStatus()          // Try to move to next status
  *             │
  *             ▼
  *       Some((oldState, newState)) or None
  * }}}
  *
  * ==Key Methods==
  *
  * '''tryUpdateConsensus(key, resources):''' Main update method, returns old/new state if changed
  *
  * '''tryLockConsensus(key, state):''' Lock state during stall detection
  *
  * '''trySpreadAck(key, kind, resources):''' Spread acknowledgment of what we've seen
  */
trait ConsensusStateUpdater[F[_], Key, Artifact, Context, Status, Outcome, Kind] {

  type StateUpdateResult = Option[(ConsensusState[Key, Status, Outcome, Kind], ConsensusState[Key, Status, Outcome, Kind])]

  def tryUpdateConsensus(key: Key, resources: ConsensusResources[Artifact, Kind]): F[StateUpdateResult]
  def tryLockConsensus(key: Key, referenceState: ConsensusState[Key, Status, Outcome, Kind]): F[StateUpdateResult]
  def trySpreadAck(
    key: Key,
    ackKind: Kind,
    resources: ConsensusResources[Artifact, Kind]
  ): F[StateUpdateResult]

}

object ConsensusStateUpdater {

  def make[F[
    _
  ]: Async: Metrics, Event, Key: Show: Order: TypeTag: Encoder, Artifact <: AnyRef, Context <: AnyRef, Status: Eq: Show, Outcome: Eq, Kind: Encoder: Eq: Show: TypeTag](
    consensusStateAdvancer: ConsensusStateAdvancer[F, Key, Artifact, Context, Status, Outcome, Kind],
    consensusStorage: ConsensusStorage[F, Event, Key, Artifact, Context, Status, Outcome, Kind],
    gossip: Gossip[F],
    statusOps: ConsensusOps[Status, Kind]
  ): ConsensusStateUpdater[F, Key, Artifact, Context, Status, Outcome, Kind] =
    new ConsensusStateUpdater[F, Key, Artifact, Context, Status, Outcome, Kind] {

      private val logger = Slf4jLogger.getLoggerFromClass(ConsensusStateUpdater.getClass)

      private val unlockConsensusFn = (resources: ConsensusResources[Artifact, Kind]) =>
        UnlockConsensusUpdate.tryUnlock[F, ConsensusState[Key, Status, Outcome, Kind], Kind](resources.acksMap)(state =>
          statusOps.maybeCollectingKind(state.status)
        )

      def tryLockConsensus(key: Key, referenceState: ConsensusState[Key, Status, Outcome, Kind]): F[StateUpdateResult] =
        tryUpdateExistingConsensus(key, lockConsensus(referenceState))

      def trySpreadAck(
        key: Key,
        ackKind: Kind,
        resources: ConsensusResources[Artifact, Kind]
      ): F[StateUpdateResult] =
        tryUpdateExistingConsensus(key, spreadAck(ackKind, resources))

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
          case (_, newState) =>
            logger.info(s"State updated ${newState.show}")
        }.void

      private def lockConsensus(
        referenceState: ConsensusState[Key, Status, Outcome, Kind]
      )(state: ConsensusState[Key, Status, Outcome, Kind]): F[(ConsensusState[Key, Status, Outcome, Kind], F[Unit])] =
        if (state.status === referenceState.status && state.lockStatus =!= LockStatus.Closed) {
          val currentKind = statusOps.maybeCollectingKind(state.status)
          // When re-locking from Reopened, clear the current collecting kind from spreadAckKinds
          // so a fresh ACK reflecting the updated facilitator set can be spread
          val clearedAckKinds =
            if (state.lockStatus === LockStatus.Reopened)
              currentKind.fold(state.spreadAckKinds)(state.spreadAckKinds.excl)
            else
              state.spreadAckKinds
          (state.copy(lockStatus = LockStatus.Closed, spreadAckKinds = clearedAckKinds), Applicative[F].unit).pure[F]
        } else
          (state, Applicative[F].unit).pure[F]

      private def spreadAck(
        ackKind: Kind,
        resources: ConsensusResources[Artifact, Kind]
      )(state: ConsensusState[Key, Status, Outcome, Kind]): F[(ConsensusState[Key, Status, Outcome, Kind], F[Unit])] =
        if (state.spreadAckKinds.contains(ackKind)) {
          if (state.lockStatus === LockStatus.Closed) {
            // Re-gossip ACK for propagation while locked; state unchanged so effect runs directly
            val ack = getAck(ackKind, resources)
            val effect = gossip.spread(ConsensusPeerDeclarationAck(state.key, ackKind, ack))
            effect.as((state, Applicative[F].unit))
          } else
            (state, Applicative[F].unit).pure[F]
        } else {
          val ack = getAck(ackKind, resources)
          val newState = state.copy(spreadAckKinds = state.spreadAckKinds.incl(ackKind))
          val effect = gossip.spread(ConsensusPeerDeclarationAck(state.key, ackKind, ack))
          (newState, effect).pure[F]
        }

      private def updateConsensus(resources: ConsensusResources[Artifact, Kind])(
        state: ConsensusState[Key, Status, Outcome, Kind]
      ): F[(ConsensusState[Key, Status, Outcome, Kind], F[Unit])] = {
        val stateAndEffect = for {
          _ <- unlockConsensusFn(resources)
          _ <- logUnlockIfHappened(state)
          _ <- updateFacilitators(resources)
          effect1 <- spreadHistoricalAck(resources)
          effect2 <- consensusStateAdvancer.advanceStatus(resources)
        } yield effect1 >> effect2

        stateAndEffect
          .run(state)
      }

      private def logUnlockIfHappened(
        originalState: ConsensusState[Key, Status, Outcome, Kind]
      ): StateT[F, ConsensusState[Key, Status, Outcome, Kind], Unit] =
        StateT.inspectF { currentState =>
          val newlyRemoved = currentState.removedFacilitators.value.diff(originalState.removedFacilitators.value)
          if (currentState.lockStatus === LockStatus.Reopened && originalState.lockStatus === LockStatus.Closed) {
            Metrics[F].incrementCounter("dag_consensus_unlock_transition") >>
              Metrics[F].incrementCounterBy("dag_consensus_unlock_peers_removed", newlyRemoved.size) >>
              logger.warn(
                s"Unlock transition: Closed -> Reopened for key=${currentState.key.show}. " +
                  s"Removed ${newlyRemoved.size} peers: ${newlyRemoved.map(_.show).mkString(", ")}. " +
                  s"Remaining facilitators: ${currentState.facilitators.value.size}"
              )
          } else Applicative[F].unit
        }

      private def updateFacilitators(
        resources: ConsensusResources[Artifact, Kind]
      ): StateT[F, ConsensusState[Key, Status, Outcome, Kind], Unit] =
        StateT { state =>
          val newState =
            if (state.lockStatus === LockStatus.Closed || resources.withdrawalsMap.isEmpty)
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
                  )
                }
                .getOrElse(state)

          val withdrawnCount = newState.withdrawnFacilitators.value.size - state.withdrawnFacilitators.value.size
          val effect =
            if (withdrawnCount > 0)
              Metrics[F].incrementCounterBy("dag_consensus_facilitator_withdrawal", withdrawnCount)
            else Applicative[F].unit

          effect.as((newState, ()))
        }

      private def spreadHistoricalAck(
        resources: ConsensusResources[Artifact, Kind]
      ): StateT[F, ConsensusState[Key, Status, Outcome, Kind], F[Unit]] =
        StateT { state =>
          resources.ackKinds
            .diff(state.spreadAckKinds)
            .intersect(statusOps.collectedKinds(state.status))
            .toList
            .foldLeft((state, Applicative[F].unit)) { (acc, ackKind) =>
              acc match {
                case (state, effect) =>
                  val ack = getAck(ackKind, resources)
                  val newState = state.copy(spreadAckKinds = state.spreadAckKinds.incl(ackKind))
                  val newEffect = gossip.spread(ConsensusPeerDeclarationAck(state.key, ackKind, ack))
                  (newState, effect >> newEffect)
              }
            }
            .pure[F]
        }

      private def getAck(ackKind: Kind, resources: ConsensusResources[Artifact, Kind]): Set[PeerId] = {
        val getter = statusOps.kindGetter(ackKind)

        val declarationAck: Set[PeerId] = resources.peerDeclarationsMap.filter {
          case (_, peerDeclarations) => getter(peerDeclarations).isDefined
        }.keySet
        val withdrawalAck: Set[PeerId] = resources.withdrawalsMap.filter {
          case (_, kind) => kind === ackKind
        }.keySet

        declarationAck.union(withdrawalAck)
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

        val forkRecovery = metrics.incrementCounter(
          "dag_consensus_fork_detected",
          Seq(unsafeLabelName("observation_type") -> observationName)
        ) >>
          Slf4jLogger
            .getLogger[F]
            .warn(s"Different hash observations [$observationName]. This node is in fork") >>
          nodeStorage.setNodeState(NodeState.Leaving) >>
          Temporal[F].sleep(leavingDelay) >>
          nodeStorage.setNodeState(NodeState.Offline) >>
          Temporal[F].sleep(5.seconds) >>
          ExitOnFork.exitOnFeature("CL_EXIT_ON_FORK") >>
          restartService.signalNodeForkedRestart(majorityForkPeers)

        // Note: fire-and-forget fiber for fork recovery. This is acceptable because fork recovery
        // is a one-shot operation (node transitions to Leaving → Offline → restart) and only fires
        // on fork detection, which is rare. Using .start rather than Supervisor because the advancers
        // don't have Supervisor in scope, and fork recovery should outlive any single round.
        Temporal[F].start(forkRecovery).void

      } else Applicative[F].unit
    }.void

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
                              s"Majority artifact validation failed hash=${majorityHash.show} with $occurrences/$totalProposals proposals. " +
                                s"Abandoning round to prevent fork (would diverge from ${occurrences} nodes)."
                            ) >> none[ArtifactInfo[Artifact, Context]].pure[F]
                      else
                        metrics.incrementCounter("dag_consensus_majority_artifact_fallback") >>
                          Slf4jLogger
                            .getLogger[F]
                            .warn(cause)(s"Found invalid majority hash=${majorityHash.show} with occurrences=$occurrences") >>
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
