package io.constellationnetwork.currency.l0.snapshot.synchronous

import cats._
import cats.data.StateT
import cats.effect.{Async, Sync, Temporal}
import cats.syntax.all._

import scala.collection.immutable.SortedMap
import scala.concurrent.duration._
import scala.reflect.runtime.universe.TypeTag

import io.constellationnetwork.currency.l0.snapshot.synchronous.ConsensusState._
import io.constellationnetwork.currency.l0.snapshot.synchronous.ConsensusStorage.ModifyStateWithEffectFn
import io.constellationnetwork.currency.l0.snapshot.synchronous.declaration.AttemptDomain
import io.constellationnetwork.currency.l0.snapshot.synchronous.message._
import io.constellationnetwork.currency.l0.snapshot.synchronous.update.UnlockConsensusUpdate
import io.constellationnetwork.ext.collection.FoldableOps.pickMajority
import io.constellationnetwork.node.shared.domain.consensus.ConsensusFunctions
import io.constellationnetwork.node.shared.domain.gossip.Gossip
import io.constellationnetwork.node.shared.domain.node.NodeStorage
import io.constellationnetwork.node.shared.domain.snapshot.services.GlobalL0Service
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.ConsensusTrigger
import io.constellationnetwork.node.shared.infrastructure.fork.ExitOnFork
import io.constellationnetwork.node.shared.infrastructure.node.RestartService
import io.constellationnetwork.schema.node.NodeState
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo, SnapshotOrdinal}
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.{Hashed, Hasher}

import eu.timepit.refined.auto._
import io.circe.Encoder
import org.typelevel.log4cats.slf4j.Slf4jLogger

trait ConsensusStateUpdater[F[_], Key, Artifact, Context, Status, Outcome, Kind] {

  type StateUpdateResult = Option[(ConsensusState[Key, Status, Outcome, Kind], ConsensusState[Key, Status, Outcome, Kind])]

  /** Tries to conditionally update a consensus based on information collected in `resources`, this includes:
    *   - unlocking consensus,
    *   - updating facilitators,
    *   - spreading historical acks,
    *   - advancing consensus status.
    *
    * Returns `Some((oldState, newState))` when the consensus with `key` exists and update was successful, otherwise `None`
    */
  def tryUpdateConsensus(key: Key, resources: ConsensusResources[Artifact, Kind]): F[StateUpdateResult]

  /** Tries to lock a consensus if the current status equals to status in the `referenceState`. Returns `Some((unlockedState, lockedState))`
    * when the consensus with `key` exists and was successfully locked, otherwise `None`
    */
  def tryLockConsensus(key: Key, referenceState: ConsensusState[Key, Status, Outcome, Kind]): F[StateUpdateResult]

  /** Tries to spread ack if it wasn't already spread. Returns `Some((oldState, newState))` when the consenus with `key` exists and spread
    * was successful, otherwise `None`
    */
  def trySpreadAck(
    key: Key,
    ackKind: Kind,
    resources: ConsensusResources[Artifact, Kind]
  ): F[StateUpdateResult]

}

object ConsensusStateUpdater {

  def make[F[
    _
  ]: Async, Event, Key: Show: Order: TypeTag: Encoder, Artifact <: AnyRef, Context <: AnyRef, Status: Eq: Show, Outcome: Eq, Kind: Encoder: Eq: Show: TypeTag](
    consensusStateAdvancer: ConsensusStateAdvancer[F, Key, Artifact, Context, Status, Outcome, Kind],
    consensusStorage: ConsensusStorage[F, Event, Key, Artifact, Context, Status, Outcome, Kind],
    gossip: Gossip[F],
    statusOps: ConsensusOps[Status, Kind],
    attemptDomain: ConsensusState[Key, Status, Outcome, Kind] => F[AttemptDomain]
  ): ConsensusStateUpdater[F, Key, Artifact, Context, Status, Outcome, Kind] =
    new ConsensusStateUpdater[F, Key, Artifact, Context, Status, Outcome, Kind] {

      private val logger = Slf4jLogger.getLoggerFromClass(ConsensusStateUpdater.getClass)

      private val unlockConsensusFn = (resources: ConsensusResources[Artifact, Kind]) =>
        StateT[F, ConsensusState[Key, Status, Outcome, Kind], Unit] { state =>
          attemptDomain(state).flatMap { domain =>
            val matchingAcks = resources.acksMap.collect {
              case (peerAndKind, (ackDomain, ack)) if ackDomain === domain => peerAndKind -> ack
            }
            UnlockConsensusUpdate
              .tryUnlock[F, ConsensusState[Key, Status, Outcome, Kind], Kind](matchingAcks)(current =>
                statusOps.maybeCollectingKind(current.status)
              )
              .run(state)
          }
        }

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
        consensusStorage.runRetainedEffect(key) >>
          consensusStorage
            .condModifyStateWithEffect(key)(toUpdateStateWithEffectFn(fn))
            .map(_.flatten)
            .flatTap(_ => consensusStorage.runRetainedEffect(key))
            .flatTap(logIfUpdatedState)

      private def toUpdateStateWithEffectFn(
        fn: ConsensusState[Key, Status, Outcome, Kind] => F[(ConsensusState[Key, Status, Outcome, Kind], F[Unit])]
      ): ModifyStateWithEffectFn[F, Key, Status, Outcome, Kind, StateUpdateResult] = { maybeState =>
        maybeState.flatTraverse { oldState =>
          fn(oldState).map {
            case (newState, effect) =>
              Option.when(newState =!= oldState)((newState.some, (oldState, newState).some, effect))
          }
        }
      }

      private def logIfUpdatedState(updateResult: StateUpdateResult): F[Unit] =
        updateResult.traverse {
          case (_, newState) =>
            logger.info(s"State updated ${newState.show}")
        }.void

      private def lockConsensus(
        referenceState: ConsensusState[Key, Status, Outcome, Kind]
      )(state: ConsensusState[Key, Status, Outcome, Kind]): F[(ConsensusState[Key, Status, Outcome, Kind], F[Unit])] =
        if (state.status === referenceState.status && state.lockStatus === LockStatus.Open)
          (state.copy(lockStatus = LockStatus.Closed), Applicative[F].unit).pure[F]
        else
          (state, Applicative[F].unit).pure[F]

      private def spreadAck(
        ackKind: Kind,
        resources: ConsensusResources[Artifact, Kind]
      )(state: ConsensusState[Key, Status, Outcome, Kind]): F[(ConsensusState[Key, Status, Outcome, Kind], F[Unit])] =
        if (state.spreadAckKinds.contains(ackKind))
          (state, Applicative[F].unit).pure[F]
        else {
          attemptDomain(state).map { domain =>
            val ack = getAck(ackKind, resources, domain)
            val newState = state.copy(spreadAckKinds = state.spreadAckKinds.incl(ackKind))
            val effect = gossip.spread(ConsensusPeerDeclarationAck(state.key, ackKind, ack, domain))
            (newState, effect)
          }
        }

      private def updateConsensus(resources: ConsensusResources[Artifact, Kind])(
        state: ConsensusState[Key, Status, Outcome, Kind]
      ): F[(ConsensusState[Key, Status, Outcome, Kind], F[Unit])] = {
        val stateAndEffect = for {
          _ <- unlockConsensusFn(resources)
          _ <- updateFacilitators(resources)
          effect1 <- spreadHistoricalAck(resources)
          effect2 <- consensusStateAdvancer.advanceStatus(resources)
        } yield effect1 >> effect2

        stateAndEffect
          .run(state)
      }

      private def updateFacilitators(
        resources: ConsensusResources[Artifact, Kind]
      ): StateT[F, ConsensusState[Key, Status, Outcome, Kind], Unit] =
        StateT.modify { state =>
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
        }

      private def spreadHistoricalAck(
        resources: ConsensusResources[Artifact, Kind]
      ): StateT[F, ConsensusState[Key, Status, Outcome, Kind], F[Unit]] =
        StateT { state =>
          attemptDomain(state).map { domain =>
            resources.ackKinds
              .diff(state.spreadAckKinds)
              .intersect(statusOps.collectedKinds(state.status))
              .toList
              .foldLeft((state, Applicative[F].unit)) { (acc, ackKind) =>
                acc match {
                  case (current, effect) =>
                    val ack = getAck(ackKind, resources, domain)
                    val newState = current.copy(spreadAckKinds = current.spreadAckKinds.incl(ackKind))
                    val newEffect = gossip.spread(ConsensusPeerDeclarationAck(current.key, ackKind, ack, domain))
                    (newState, effect >> newEffect)
                }
              }
          }
        }

      private def getAck(ackKind: Kind, resources: ConsensusResources[Artifact, Kind], domain: AttemptDomain): Set[PeerId] = {
        val getter = statusOps.kindGetter(ackKind)

        val declarationAck: Set[PeerId] = resources.peerDeclarationsMap.filter {
          case (_, peerDeclarations) => getter(peerDeclarations).exists(_.domain === domain)
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
  ): F[Unit] =
    pickMajority(observations.values.toList).traverse { majorityObservationHash =>
      val isForked = majorityObservationHash =!= ownObservationHash

      if (isForked) {
        val majorityForkPeers = observations.collect {
          case (peerId, observationHash) if observationHash === majorityObservationHash => peerId
        }.toList

        val forkRecovery = Slf4jLogger
          .getLogger[F]
          .warn(s"Different hash observations [$observationName]. This node is in fork") >>
          nodeStorage.setNodeState(NodeState.Leaving) >>
          Temporal[F].sleep(leavingDelay) >>
          nodeStorage.setNodeState(NodeState.Offline) >>
          Temporal[F].sleep(5.seconds) >>
          ExitOnFork.exitOnFeature("CL_EXIT_ON_FORK") >>
          restartService.signalNodeForkedRestart(majorityForkPeers)

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
  )(implicit hasher: Hasher[F]): F[Option[ArtifactInfo[Artifact, Context]]] = {
    def go(proposals: List[(Int, Hash)]): F[Option[ArtifactInfo[Artifact, Context]]] =
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
                      Slf4jLogger
                        .getLogger[F]
                        .warn(cause)(s"Found invalid majority hash=${majorityHash.show} with occurrences=$occurrences") >> go(tail),
                    ai => ai.some.pure[F]
                  )
                }
              }
        case Nil => none[ArtifactInfo[Artifact, Context]].pure[F]
      }

    val sortedProposals = proposals.foldMap(a => Map(a -> 1)).toList.map(_.swap).sorted.reverse
    go(sortedProposals)
  }

  def proposalAffinity[A: Order](proposals: List[A], proposal: A): Double =
    if (proposals.nonEmpty)
      proposals.count(Order[A].eqv(proposal, _)).toDouble / proposals.size.toDouble
    else
      0.0

}
