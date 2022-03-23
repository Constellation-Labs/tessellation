package org.tessellation.sdk.infrastructure.consensus

import java.security.KeyPair

import cats.data.NonEmptyList
import cats.effect.Async
import cats.effect.kernel.Clock
import cats.kernel.Next
import cats.syntax.applicative._
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
import org.tessellation.sdk.infrastructure.consensus.message._
import org.tessellation.security.SecurityProvider
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed
import org.tessellation.security.signature.signature.{Signature, SignatureProof}

import org.typelevel.log4cats.slf4j.Slf4jLogger

trait ConsensusStateUpdater[F[_], Key, Artifact] {

  type MaybeState = Option[ConsensusState[Key, Artifact]]

  def tryAdvanceConsensus(key: Key, resources: ConsensusResources[Artifact]): F[MaybeState]

  def tryFacilitateConsensus(key: Key, lastKeyAndArtifact: (Key, Artifact)): F[MaybeState]

  def tryRemoveFacilitator(key: Key, facilitator: PeerId): F[MaybeState]
}

object ConsensusStateUpdater {

  def make[F[_]: Async: Clock: KryoSerializer: SecurityProvider, Event, Key: Show: Order: Next: TypeTag: ClassTag, Artifact <: AnyRef: Show: TypeTag](
    consensusFns: ConsensusFunctions[F, Event, Key, Artifact],
    consensusStorage: ConsensusStorage[F, Event, Key, Artifact],
    clusterStorage: ClusterStorage[F],
    gossip: Gossip[F],
    keyPair: KeyPair,
    selfId: PeerId
  ): ConsensusStateUpdater[F, Key, Artifact] = new ConsensusStateUpdater[F, Key, Artifact] {

    private val logger = Slf4jLogger.getLoggerFromClass(ConsensusStateUpdater.getClass)

    def tryFacilitateConsensus(key: Key, lastKeyAndArtifact: (Key, Artifact)): F[MaybeState] =
      tryModifyConsensus(key, internalTryFacilitateConsensus(key, lastKeyAndArtifact))
        .flatTap(logStatusIfModified(key))

    def tryAdvanceConsensus(key: Key, resources: ConsensusResources[Artifact]): F[MaybeState] =
      tryModifyConsensus(key, internalTryAdvanceConsensus(key, resources))
        .flatTap(logStatusIfModified(key))

    def tryRemoveFacilitator(key: Key, facilitator: PeerId): F[MaybeState] =
      tryModifyConsensus(key, internalTryRemoveFacilitator(facilitator))
        .flatTap(logFacilitatorIfModified(key, facilitator))

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
        logger.debug(s"Consensus status for key ${key.show} transitioned to ${state.status.show}")
      }.void

    private def logFacilitatorIfModified(key: Key, facilitator: PeerId)(maybeState: MaybeState): F[Unit] =
      maybeState.traverse { state =>
        logger.debug(
          s"Consensus facilitators for key ${key.show} updated. ${facilitator.show} removed, " +
            s"${state.facilitators.size.show} facilitators remaining"
        )
      }.void

    private def internalTryRemoveFacilitator(facilitator: PeerId)(
      maybeState: MaybeState
    ): F[Option[(ConsensusState[Key, Artifact], F[Unit])]] =
      maybeState.filter { state =>
        state.status match {
          case _: Finished[Artifact] => false
          case _                     => state.facilitators.contains(facilitator)
        }
      }.map { state =>
        (state.copy(facilitators = state.facilitators.filter(_ != facilitator)), Applicative[F].unit)
      }.pure[F]

    private def internalTryFacilitateConsensus(key: Key, lastKeyAndArtifact: (Key, Artifact))(
      maybeState: MaybeState
    ): F[Option[(ConsensusState[Key, Artifact], F[Unit])]] =
      maybeState match {
        case None =>
          for {
            peers <- clusterStorage.getPeers
            facilitators = selfId :: NodeState.ready(peers).map(_.id).toList
            time <- Clock[F].realTime
            state = ConsensusState(key, facilitators, lastKeyAndArtifact, Facilitated[Artifact](), time)
            bound <- consensusStorage.getUpperBound
            effect = gossip.spread(ConsensusFacility(key, bound))
          } yield (state, effect).some
        case Some(consensusState) =>
          logger.debug(s"Consensus state for key ${key.show} already exists ${consensusState.show}") >>
            none.pure[F]
      }

    private def internalTryAdvanceConsensus(key: Key, resources: ConsensusResources[Artifact])(
      maybeState: MaybeState
    ): F[Option[(ConsensusState[Key, Artifact], F[Unit])]] =
      maybeState.flatTraverse { state =>
        state.status match {
          case Facilitated() =>
            val maybeBound = state.facilitators
              .traverse(resources.peerDeclarations.get)
              .flatMap(_.foldMap(_.upperBound))

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
                effect = gossip.spread(ConsensusProposal(key, hash))
              } yield (newState, effect)
            }
          case ProposalMade(proposalHash, proposalArtifact) =>
            val maybeMajority = state.facilitators
              .traverse(resources.peerDeclarations.get)
              .flatMap(_.traverse(_.proposal))
              .flatMap(pickMajority)

            maybeMajority.traverse { majorityHash =>
              val newState = state.copy(status = MajoritySigned[Artifact](majorityHash))
              for {
                signature <- Signature.fromHash(keyPair.getPrivate, majorityHash)
                effect = gossip.spread(MajoritySignature(key, signature)) >> {
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
                resources.peerDeclarations
                  .get(peerId)
                  .flatMap(peerDeclaration => peerDeclaration.signature.map(signature => (peerId, signature)))
              }.map(_.map { case (id, signature) => SignatureProof(PeerId._Id.get(id), signature) })

            val maybeSignedArtifact = for {
              allSignatures <- maybeAllSignatures
              allSignaturesNel <- NonEmptyList.fromList(allSignatures)
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
  }

}
