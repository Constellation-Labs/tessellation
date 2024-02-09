package org.tessellation.node.shared.infrastructure.consensus

import cats._
import cats.data.StateT
import cats.effect.{Async, Sync}
import cats.syntax.all._

import scala.reflect.runtime.universe.TypeTag

import org.tessellation.ext.collection.FoldableOps.pickMajority
import org.tessellation.node.shared.domain.consensus.ConsensusFunctions
import org.tessellation.node.shared.domain.gossip.Gossip
import org.tessellation.node.shared.infrastructure.consensus.ConsensusState._
import org.tessellation.node.shared.infrastructure.consensus.ConsensusStorage.ModifyStateFn
import org.tessellation.node.shared.infrastructure.consensus.message._
import org.tessellation.node.shared.infrastructure.consensus.trigger.ConsensusTrigger
import org.tessellation.node.shared.infrastructure.consensus.update.UnlockConsensusUpdate
import org.tessellation.schema.peer.PeerId
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed

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

  def warnIfForking[F[_]: Sync](ownObservationHash: Hash, observationName: String)(
    observations: List[Hash]
  ): F[Unit] =
    pickMajority(observations).traverse { majorityObservationHash =>
      Slf4jLogger
        .getLogger[F]
        .warn(s"Different hash observations [$observationName]. This node is in fork")
        .whenA(majorityObservationHash =!= ownObservationHash)
    }.void

  def pickValidatedMajorityArtifact[F[_]: Sync, Event, Key, Artifact, Context, Kind](
    ownProposalInfo: ArtifactInfo[Artifact, Context],
    lastSignedArtifact: Signed[Artifact],
    lastContext: Context,
    trigger: ConsensusTrigger,
    resources: ConsensusResources[Artifact, Kind],
    proposals: List[Hash],
    facilitators: Set[PeerId],
    consensusFns: ConsensusFunctions[F, Event, Key, Artifact, Context]
  ): F[Option[ArtifactInfo[Artifact, Context]]] = {
    def go(proposals: List[(Int, Hash)]): F[Option[ArtifactInfo[Artifact, Context]]] =
      proposals match {
        case (occurrences, majorityHash) :: tail =>
          if (majorityHash === ownProposalInfo.hash)
            ownProposalInfo.some.pure[F]
          else
            resources.artifacts
              .get(majorityHash)
              .traverse { artifact =>
                consensusFns.validateArtifact(lastSignedArtifact, lastContext, trigger, artifact, facilitators).map {
                  validationResultOrError =>
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
