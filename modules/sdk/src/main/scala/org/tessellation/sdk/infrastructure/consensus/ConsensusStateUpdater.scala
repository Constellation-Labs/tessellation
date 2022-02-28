package org.tessellation.sdk.infrastructure.consensus

import java.security.KeyPair

import cats.data.NonEmptyList
import cats.effect.Async
import cats.kernel.Next
import cats.syntax.all.none
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

  def tryAdvanceConsensus(
    key: Key,
    resources: ConsensusResources[Artifact]
  ): F[Option[ConsensusState[Key, Artifact]]]

  def tryFacilitateConsensus(
    key: Key,
    lastKeyAndArtifact: (Key, Artifact)
  ): F[Option[ConsensusState[Key, Artifact]]]
}

object ConsensusStateUpdater {

  def make[F[_]: Async: KryoSerializer: SecurityProvider, Event, Key: Show: Order: Next: TypeTag: ClassTag, Artifact <: AnyRef: Show: TypeTag](
    consensusFns: ConsensusFunctions[F, Event, Key, Artifact],
    consensusStorage: ConsensusStorage[F, Event, Key, Artifact],
    clusterStorage: ClusterStorage[F],
    gossip: Gossip[F],
    keyPair: KeyPair,
    selfId: PeerId
  ): ConsensusStateUpdater[F, Key, Artifact] = new ConsensusStateUpdater[F, Key, Artifact] {

    private val logger = Slf4jLogger.getLoggerFromClass(ConsensusStateUpdater.getClass)

    def tryFacilitateConsensus(
      key: Key,
      lastKeyAndArtifact: (Key, Artifact)
    ): F[Option[ConsensusState[Key, Artifact]]] =
      consensusStorage
        .updateState(key)(logStatusIfUpdated(internalTryFacilitateConsensus(key, lastKeyAndArtifact)))

    def tryAdvanceConsensus(
      key: Key,
      resources: ConsensusResources[Artifact]
    ): F[Option[ConsensusState[Key, Artifact]]] =
      consensusStorage
        .updateState(key)(logStatusIfUpdated(internalTryAdvanceConsensus(key, resources)))

    import consensusStorage.StateUpdateFn

    private def logStatusIfUpdated(fn: StateUpdateFn): StateUpdateFn =
      fn(_, _).flatTap {
        case Some((true, newState)) =>
          logger.debug(s"Consensus status for key ${newState.key.show} transitioned to ${newState.status.show}")
        case _ => Applicative[F].unit
      }

    private def internalTryFacilitateConsensus(key: Key, lastKeyAndArtifact: (Key, Artifact))(
      maybeState: Option[ConsensusState[Key, Artifact]],
      setter: Option[ConsensusState[Key, Artifact]] => F[Boolean]
    ): F[Option[(Boolean, ConsensusState[Key, Artifact])]] =
      maybeState match {
        case None =>
          for {
            peers <- clusterStorage.getPeers
            facilitators = selfId :: NodeState.ready(peers).map(_.id).toList
            state = ConsensusState(key, facilitators, lastKeyAndArtifact, Facilitated[Artifact]())
            result <- setter(state.some)
            bound <- consensusStorage.getUpperBound
            _ <- gossip.spread(ConsensusFacility(key, bound))
          } yield (result, state).some
        case Some(consensusState) =>
          logger.debug(s"Consensus state for key ${key.show} already exists ${consensusState.show}") >>
            Applicative[F].pure(none[(Boolean, ConsensusState[Key, Artifact])])
      }

    private def internalTryAdvanceConsensus(key: Key, resources: ConsensusResources[Artifact])(
      maybeState: Option[ConsensusState[Key, Artifact]],
      setter: Option[ConsensusState[Key, Artifact]] => F[Boolean]
    ): F[Option[(Boolean, ConsensusState[Key, Artifact])]] =
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
                result <- setter(newState.some)
                _ <- gossip.spread(ConsensusProposal(key, hash))
              } yield (result, newState)
            }
          case ProposalMade(proposalHash, proposalArtifact) =>
            val maybeMajority = state.facilitators
              .traverse(resources.peerDeclarations.get)
              .flatMap(_.traverse(_.proposal))
              .flatMap(pickMajority)

            maybeMajority.traverse { majorityHash =>
              val newState = state.copy(status = MajoritySelected[Artifact](majorityHash))
              for {
                result <- setter(newState.some)
                signature <- Signature.fromHash(keyPair.getPrivate, majorityHash)
                _ <- gossip.spread(MajoritySignature(key, signature))
                _ <- if (majorityHash == proposalHash)
                  gossip.spreadCommon(ConsensusArtifact(key, proposalArtifact))
                else
                  Applicative[F].unit
              } yield (result, newState)

            }
          case MajoritySelected(majorityHash) =>
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
              val newState = state.copy(status = MajoritySigned(signedArtifact))
              for {
                result <- setter(newState.some)
                _ <- consensusFns.consumeSignedMajorityArtifact(signedArtifact)
                _ <- gossip.spreadCommon(ConsensusArtifact(key, signedArtifact))
                _ <- if (result)
                  logger.info(s"Consensus for key ${key.show} finished. Majority artifact hash is ${majorityHash.show}")
                else
                  Applicative[F].unit
              } yield (result, newState)
            }

          case MajoritySigned(_) =>
            Applicative[F].pure(none)
        }
      }

    private def pickMajority(proposals: List[Hash]): Option[Hash] =
      proposals.foldMap(h => Map(h -> 1)).toList.map(_.swap).maximumOption.map(_._2)
  }

}
