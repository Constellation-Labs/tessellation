package org.tessellation.sdk.infrastructure.consensus

import java.security.KeyPair

import cats.data.NonEmptySet
import cats.effect.Async
import cats.effect.kernel.Clock
import cats.kernel.Next
import cats.syntax.applicative._
import cats.syntax.eq._
import cats.syntax.flatMap._
import cats.syntax.foldable._
import cats.syntax.functor._
import cats.syntax.option._
import cats.syntax.show._
import cats.syntax.traverse._
import cats.{Applicative, Order, Show}

import scala.reflect.ClassTag
import scala.reflect.runtime.universe.TypeTag

import org.tessellation.ext.crypto._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.node.NodeState
import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.domain.cluster.storage.ClusterStorage
import org.tessellation.sdk.domain.consensus.ConsensusFunctions
import org.tessellation.sdk.domain.gossip.Gossip
import org.tessellation.sdk.infrastructure.consensus.declaration.{Facility, MajoritySignature, Proposal}
import org.tessellation.sdk.infrastructure.consensus.message._
import org.tessellation.security.SecurityProvider
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed
import org.tessellation.security.signature.signature.{Signature, SignatureProof}
import org.tessellation.syntax.sortedCollection._

import org.typelevel.log4cats.slf4j.Slf4jLogger

trait ConsensusStateUpdater[F[_], Key, Artifact] {

  type MaybeState = Option[ConsensusState[Key, Artifact]]

  def tryUpdateConsensus(key: Key, resources: ConsensusResources[Artifact]): F[MaybeState]

  def tryFacilitateConsensus(
    key: Key,
    resources: ConsensusResources[Artifact],
    lastKeyAndArtifact: (Key, Signed[Artifact])
  ): F[MaybeState]

}

object ConsensusStateUpdater {

  def make[F[_]: Async: Clock: KryoSerializer: SecurityProvider, Event, Key: Show: Order: Next: TypeTag: ClassTag, Artifact <: AnyRef: Show: TypeTag](
    consensusFns: ConsensusFunctions[F, Event, Key, Artifact],
    consensusStorage: ConsensusStorage[F, Event, Key, Artifact],
    clusterStorage: ClusterStorage[F],
    gossip: Gossip[F],
    whitelisting: Option[Set[PeerId]],
    keyPair: KeyPair,
    selfId: PeerId
  ): ConsensusStateUpdater[F, Key, Artifact] = new ConsensusStateUpdater[F, Key, Artifact] {

    private val logger = Slf4jLogger.getLoggerFromClass(ConsensusStateUpdater.getClass)

    def tryFacilitateConsensus(
      key: Key,
      resources: ConsensusResources[Artifact],
      lastKeyAndArtifact: (Key, Signed[Artifact])
    ): F[MaybeState] =
      tryModifyConsensus(key, internalTryFacilitateConsensus(key, resources, lastKeyAndArtifact))
        .flatTap(logStatusIfModified(key))

    def tryUpdateConsensus(key: Key, resources: ConsensusResources[Artifact]): F[MaybeState] =
      tryModifyConsensus(key, internalTryUpdateConsensus(key, resources))
        .flatTap(logStatusIfModified(key))

    import consensusStorage.ModifyStateFn

    private def tryModifyConsensus(
      key: Key,
      fn: MaybeState => F[Option[(ConsensusState[Key, Artifact], F[Unit])]]
    ): F[MaybeState] =
      consensusStorage
        .condModifyState(key)(toModifyStateFn(fn))
        .flatMap(evalEffect)

    private def toModifyStateFn(
      fn: MaybeState => F[Option[(ConsensusState[Key, Artifact], F[Unit])]]
    ): ModifyStateFn[(MaybeState, F[Unit])] =
      maybeState =>
        fn(maybeState).map(_.map {
          case (state, effect) => (state.some, (state.some, effect))
        })

    private def evalEffect(result: Option[(MaybeState, F[Unit])]): F[MaybeState] =
      result.flatTraverse {
        case (maybeState, effect) => effect.map(_ => maybeState)
      }

    private def logStatusIfModified(key: Key)(maybeState: MaybeState): F[Unit] =
      maybeState.traverse { state =>
        logger.debug {
          s"Consensus for key ${key.show} has ${state.facilitators.size} facilitator(s) and status ${state.status.show}"
        }
      }.void

    private def internalTryFacilitateConsensus(
      key: Key,
      resources: ConsensusResources[Artifact],
      lastKeyAndArtifact: (Key, Signed[Artifact])
    )(
      maybeState: MaybeState
    ): F[Option[(ConsensusState[Key, Artifact], F[Unit])]] =
      maybeState match {
        case None =>
          for {
            peers <- clusterStorage.getPeers
            readyPeers = NodeState.ready(peers).map(_.id) + selfId
            facilitators = calculateFacilitators(
              resources.proposedFacilitators,
              readyPeers,
              resources.removedFacilitators
            )
            time <- Clock[F].realTime
            state = ConsensusState(
              key,
              facilitators.toList.sorted,
              lastKeyAndArtifact,
              Facilitated[Artifact](),
              time
            )
            bound <- consensusStorage.getUpperBound
            effect = gossip.spread(ConsensusPeerDeclaration(key, Facility(bound, facilitators)))
          } yield (state, effect).some
        case Some(_) =>
          none.pure[F]
      }

    private def internalTryUpdateConsensus(key: Key, resources: ConsensusResources[Artifact])(
      maybeState: MaybeState
    ): F[Option[(ConsensusState[Key, Artifact], F[Unit])]] = {
      val maybeUpdatedState = maybeState
        .flatMap(internalTryUpdateFacilitators(resources))

      maybeUpdatedState
        .orElse(maybeState)
        .flatTraverse(internalTryUpdateStatus(key, resources))
        .map(_.orElse(maybeUpdatedState.map(state => (state, Applicative[F].unit))))
    }

    /**
      * Updates the facilitator list if necessary
      * @return Some(newState) if facilitator list required update, otherwise None
      */
    private def internalTryUpdateFacilitators(resources: ConsensusResources[Artifact])(
      state: ConsensusState[Key, Artifact]
    ): MaybeState = {
      state.status match {
        case Facilitated() =>
          calculateFacilitators(
            resources.proposedFacilitators,
            state.facilitators.toSet,
            resources.removedFacilitators
          ).some
        case Finished(_) =>
          none
        case _ =>
          calculateRemainingFacilitators(state.facilitators.toSet, resources.removedFacilitators).some
      }
    }.map(_.toList.sorted).flatMap { facilitators =>
      if (facilitators =!= state.facilitators)
        state.copy(facilitators = facilitators).some
      else
        none
    }

    /**
      * Updates the status if necessary
      * @return Some(newState) if state required update, otherwise None
      */
    private def internalTryUpdateStatus(key: Key, resources: ConsensusResources[Artifact])(
      state: ConsensusState[Key, Artifact]
    ): F[Option[(ConsensusState[Key, Artifact], F[Unit])]] = {
      state.status match {
        case Facilitated() =>
          val maybeBound = state.facilitators
            .traverse(resources.peerDeclarationsMap.get)
            .flatMap(_.foldMap(_.facility.map(_.upperBound)))

          maybeBound.traverse { bound =>
            for {
              peerEvents <- consensusStorage.pullEvents(bound)
              events = peerEvents.toList.flatMap(_._2).map(_._2).toSet
              (artifact, returnedEvents) <- consensusFns
                .createProposalArtifact(state.lastKeyAndArtifact, events)
              returnedPeerEvents = peerEvents.map {
                case (peerId, events) =>
                  (peerId, events.filter { case (_, event) => returnedEvents.contains(event) })
              }.filter { case (_, events) => events.nonEmpty }
              _ <- consensusStorage.addEvents(returnedPeerEvents)
              hash <- artifact.hashF
              newState = state.copy(status = ProposalMade[Artifact](hash, artifact))
              effect = gossip.spread(ConsensusPeerDeclaration(key, Proposal(hash)))
            } yield (newState, effect)
          }
        case ProposalMade(proposalHash, proposalArtifact) =>
          val maybeMajority = state.facilitators
            .traverse(resources.peerDeclarationsMap.get)
            .flatMap(_.traverse(_.proposal.map(_.hash)))
            .flatMap(pickMajority)

          maybeMajority.traverse { majorityHash =>
            val newState = state.copy(status = MajoritySigned[Artifact](majorityHash))
            for {
              signature <- Signature.fromHash(keyPair.getPrivate, majorityHash)
              effect = gossip.spread(ConsensusPeerDeclaration(key, MajoritySignature(signature))) >> {
                if (majorityHash == proposalHash)
                  gossip.spreadCommon(ConsensusArtifact(key, proposalArtifact))
                else
                  Applicative[F].unit
              }
            } yield (newState, effect)
          }
        case MajoritySigned(majorityHash) =>
          val maybeAllSignatures =
            state.facilitators.sorted.traverse { peerId =>
              resources.peerDeclarationsMap
                .get(peerId)
                .flatMap(peerDeclaration => peerDeclaration.signature.map(signature => (peerId, signature.signature)))
            }.map(_.map { case (id, signature) => SignatureProof(PeerId._Id.get(id), signature) }.toSet)

          val maybeSignedArtifact = for {
            allSignatures <- maybeAllSignatures
            allSignaturesNel <- NonEmptySet.fromSet(allSignatures.toSortedSet)
            majorityArtifact <- resources.artifacts.get(majorityHash)
          } yield Signed(majorityArtifact, allSignaturesNel)

          maybeSignedArtifact.traverse { signedArtifact =>
            val newState = state.copy(status = Finished(signedArtifact))
            consensusFns.consumeSignedMajorityArtifact(signedArtifact).map { _ =>
              val effect = gossip.spreadCommon(ConsensusArtifact(key, signedArtifact))
              (newState, effect)
            }
          }
        case Finished(_) =>
          none[(ConsensusState[Key, Artifact], F[Unit])].pure[F]
      }
    }.flatMap(_.traverse {
      case (state, effect) =>
        Clock[F].realTime.map { time =>
          (state.copy(statusUpdatedAt = time), effect)
        }
    })

    private def pickMajority(proposals: List[Hash]): Option[Hash] =
      proposals.foldMap(h => Map(h -> 1)).toList.map(_.swap).maximumOption.map(_._2)

    private def calculateFacilitators(
      proposed: Set[PeerId],
      local: Set[PeerId],
      removed: Set[PeerId]
    ): Set[PeerId] = {
      val allProposed = proposed.union(local)
      whitelisting
        .map(_.intersect(allProposed))
        .getOrElse(allProposed)
        .diff(removed)
    }

    private def calculateRemainingFacilitators(local: Set[PeerId], removed: Set[PeerId]): Set[PeerId] =
      local.diff(removed)

  }

}
