package org.tessellation.sdk.infrastructure.consensus

import java.security.KeyPair

import cats.data.NonEmptySet
import cats.effect.Async
import cats.effect.kernel.Clock
import cats.kernel.Next
import cats.syntax.applicative._
import cats.syntax.flatMap._
import cats.syntax.foldable._
import cats.syntax.functor._
import cats.syntax.option._
import cats.syntax.order._
import cats.syntax.show._
import cats.syntax.traverse._
import cats.syntax.traverseFilter._
import cats.{Applicative, Order, Show}

import scala.reflect.runtime.universe.TypeTag

import org.tessellation.ext.crypto._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.domain.consensus.ConsensusFunctions
import org.tessellation.sdk.domain.gossip.Gossip
import org.tessellation.sdk.infrastructure.consensus.declaration.{Facility, MajoritySignature, Proposal}
import org.tessellation.sdk.infrastructure.consensus.message._
import org.tessellation.sdk.infrastructure.consensus.trigger.{ConsensusTrigger, TimeTrigger}
import org.tessellation.sdk.infrastructure.metrics.Metrics
import org.tessellation.security.SecurityProvider
import org.tessellation.security.signature.Signed
import org.tessellation.security.signature.signature.{Signature, SignatureProof, verifySignatureProof}
import org.tessellation.syntax.sortedCollection._

import eu.timepit.refined.auto._
import io.circe.Encoder
import org.typelevel.log4cats.slf4j.Slf4jLogger

trait ConsensusStateUpdater[F[_], Key, Artifact] {

  type MaybeState = Option[ConsensusState[Key, Artifact]]

  def tryUpdateConsensus(key: Key, resources: ConsensusResources[Artifact]): F[MaybeState]

  def tryFacilitateConsensus(
    key: Key,
    lastKey: Key,
    lastArtifact: Signed[Artifact],
    maybeTrigger: Option[ConsensusTrigger],
    resources: ConsensusResources[Artifact]
  ): F[MaybeState]

  def tryObserveConsensus(
    key: Key,
    lastKey: Key,
    resources: ConsensusResources[Artifact],
    initialPeer: PeerId
  ): F[MaybeState]

}

object ConsensusStateUpdater {

  def make[F[
    _
  ]: Async: Clock: KryoSerializer: SecurityProvider: Metrics, Event, Key: Show: Order: Next: TypeTag: Encoder, Artifact <: AnyRef: Show: TypeTag: Encoder](
    consensusFns: ConsensusFunctions[F, Event, Key, Artifact],
    consensusStorage: ConsensusStorage[F, Event, Key, Artifact],
    facilitatorCalculator: FacilitatorCalculator,
    gossip: Gossip[F],
    keyPair: KeyPair,
    selfId: PeerId
  ): ConsensusStateUpdater[F, Key, Artifact] = new ConsensusStateUpdater[F, Key, Artifact] {

    private val logger = Slf4jLogger.getLoggerFromClass(ConsensusStateUpdater.getClass)

    def tryFacilitateConsensus(
      key: Key,
      lastKey: Key,
      lastArtifact: Signed[Artifact],
      maybeTrigger: Option[ConsensusTrigger],
      resources: ConsensusResources[Artifact]
    ): F[MaybeState] =
      tryModifyConsensus(key, internalTryFacilitateConsensus(key, lastKey, lastArtifact, maybeTrigger, resources))
        .flatTap(logStatusIfModified(key))

    def tryObserveConsensus(
      key: Key,
      lastKey: Key,
      resources: ConsensusResources[Artifact],
      initialPeer: PeerId
    ): F[MaybeState] =
      tryModifyConsensus(key, internalTryObserveConsensus(key, lastKey, resources, initialPeer))
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
        logger.info {
          s"Consensus for key ${key.show} has ${state.facilitators.size} facilitator(s) and status ${state.status.show}"
        }
      }.void

    private def internalTryObserveConsensus(
      key: Key,
      lastKey: Key,
      resources: ConsensusResources[Artifact],
      initialPeer: PeerId
    )(
      maybeState: MaybeState
    ): F[Option[(ConsensusState[Key, Artifact], F[Unit])]] =
      maybeState match {
        case None =>
          Clock[F].monotonic.map { time =>
            val facilitators = facilitatorCalculator.calculate(
              resources.peerDeclarationsMap,
              List(initialPeer),
              resources.removedFacilitators
            )
            val state = ConsensusState(
              key,
              lastKey,
              facilitators.sorted,
              CollectingFacilities(none[FacilityInfo[Artifact]]),
              time,
              time
            )
            val effect = Applicative[F].unit
            (state, effect).some
          }
        case Some(_) =>
          none.pure[F]
      }

    private def internalTryFacilitateConsensus(
      key: Key,
      lastKey: Key,
      lastArtifact: Signed[Artifact],
      maybeTrigger: Option[ConsensusTrigger],
      resources: ConsensusResources[Artifact]
    )(
      maybeState: MaybeState
    ): F[Option[(ConsensusState[Key, Artifact], F[Unit])]] =
      maybeState match {
        case None =>
          for {
            registeredPeers <- consensusStorage.getRegisteredPeers(key)
            facilitators = facilitatorCalculator.calculate(
              resources.peerDeclarationsMap,
              selfId :: registeredPeers,
              resources.removedFacilitators
            )
            time <- Clock[F].realTime
            effect = consensusStorage.getUpperBound.flatMap { bound =>
              gossip.spread(ConsensusPeerDeclaration(key, Facility(bound, facilitators.toSet, maybeTrigger)))
            }
            state = ConsensusState(
              key,
              lastKey,
              facilitators,
              CollectingFacilities(FacilityInfo[Artifact](lastArtifact, maybeTrigger).some),
              time,
              time
            )
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

    /** Updates the facilitator list if necessary
      * @return
      *   Some(newState) if facilitator list required update, otherwise None
      */
    private def internalTryUpdateFacilitators(resources: ConsensusResources[Artifact])(
      state: ConsensusState[Key, Artifact]
    ): MaybeState = {
      state.status match {
        case CollectingFacilities(_) =>
          facilitatorCalculator
            .calculate(
              resources.peerDeclarationsMap,
              state.facilitators,
              resources.removedFacilitators
            )
            .some
        case Finished(_, _) =>
          none
        case _ =>
          facilitatorCalculator.calculateRemaining(state.facilitators, resources.removedFacilitators).some
      }
    }.flatMap { facilitators =>
      if (facilitators =!= state.facilitators)
        state.copy(facilitators = facilitators).some
      else
        none
    }

    /** Updates the status if necessary
      * @return
      *   Some(newState) if state required update, otherwise None
      */
    private def internalTryUpdateStatus(key: Key, resources: ConsensusResources[Artifact])(
      state: ConsensusState[Key, Artifact]
    ): F[Option[(ConsensusState[Key, Artifact], F[Unit])]] = {
      state.status match {
        case CollectingFacilities(maybeFacilityInfo) =>
          val maybeBoundAndTrigger = state.facilitators
            .traverse(resources.peerDeclarationsMap.get)
            .flatMap(_.traverse(_.facility))
            .map(_.foldMap(f => (f.upperBound, f.trigger.toList)))
            .flatMap { case (bound, triggers) => pickMajority(triggers).map((bound, _)) }

          maybeBoundAndTrigger.traverse {
            case (bound, majorityTrigger) =>
              Applicative[F].whenA(majorityTrigger === TimeTrigger)(consensusStorage.clearTimeTrigger) >>
                maybeFacilityInfo.traverse { facilityInfo =>
                  for {
                    peerEvents <- consensusStorage.pullEvents(bound)
                    events = peerEvents.toList.flatMap(_._2).map(_._2).toSet
                    (artifact, returnedEvents) <- consensusFns
                      .createProposalArtifact(state.key, facilityInfo.lastSignedArtifact, majorityTrigger, events)
                    returnedPeerEvents = peerEvents.map {
                      case (peerId, events) =>
                        (peerId, events.filter { case (_, event) => returnedEvents.contains(event) })
                    }.filter { case (_, events) => events.nonEmpty }
                    _ <- consensusStorage.addEvents(returnedPeerEvents)
                    hash <- artifact.hashF
                    effect = gossip.spread(ConsensusPeerDeclaration(key, Proposal(hash)))
                  } yield (ProposalInfo[Artifact](artifact, hash).some, effect)
                }.map(_.getOrElse((none[ProposalInfo[Artifact]], Applicative[F].unit))).map {
                  case (maybeProposalInfo, effect) =>
                    val newState = state.copy(status = CollectingProposals[Artifact](majorityTrigger, maybeProposalInfo))
                    (newState, effect)
                }
          }
        case CollectingProposals(majorityTrigger, maybeProposalInfo) =>
          val maybeAllProposals = state.facilitators
            .traverse(resources.peerDeclarationsMap.get)
            .flatMap(_.traverse(_.proposal.map(_.hash)))

          maybeAllProposals.flatTraverse { allProposals =>
            pickMajority(allProposals).traverse { majorityHash =>
              val newState = state.copy(status = CollectingSignatures[Artifact](majorityHash, majorityTrigger))
              val effect = maybeProposalInfo.traverse { proposalInfo =>
                Signature.fromHash(keyPair.getPrivate, majorityHash).flatMap { signature =>
                  gossip.spread(ConsensusPeerDeclaration(key, MajoritySignature(signature)))
                } >> Applicative[F].whenA(majorityHash === proposalInfo.proposalArtifactHash) {
                  gossip.spreadCommon(ConsensusArtifact(key, proposalInfo.proposalArtifact))
                } >> Metrics[F].recordDistribution(
                  "dag_consensus_proposal_affinity",
                  proposalAffinity(allProposals, proposalInfo.proposalArtifactHash)
                )
              }.void
              (newState, effect).pure[F]
            }
          }
        case CollectingSignatures(majorityHash, majorityTrigger) =>
          val maybeAllSignatures =
            state.facilitators.sorted.traverse { peerId =>
              resources.peerDeclarationsMap
                .get(peerId)
                .flatMap(peerDeclaration => peerDeclaration.signature.map(signature => (peerId, signature.signature)))
            }.map(_.map { case (id, signature) => SignatureProof(PeerId._Id.get(id), signature) }.toList)

          maybeAllSignatures.traverse { allSignatures =>
            allSignatures
              .filterA(verifySignatureProof(majorityHash, _))
              .flatTap { validSignatures =>
                logger
                  .warn(
                    s"Removed ${(allSignatures.size - validSignatures.size).show} invalid signatures during consensus for key ${key.show}, " +
                      s"${validSignatures.size.show} valid signatures left"
                  )
                  .whenA(allSignatures.size =!= validSignatures.size)
              }
          }.map { maybeOnlyValidSignatures =>
            for {
              validSignatures <- maybeOnlyValidSignatures
              validSignaturesNel <- NonEmptySet.fromSet(validSignatures.toSortedSet)
              majorityArtifact <- resources.artifacts.get(majorityHash)
              signedArtifact = Signed(majorityArtifact, validSignaturesNel)
              newState = state.copy(status = Finished[Artifact](signedArtifact, majorityTrigger))
              effect = consensusFns.consumeSignedMajorityArtifact(signedArtifact)
            } yield (newState, effect)
          }
        case Finished(_, _) =>
          none[(ConsensusState[Key, Artifact], F[Unit])].pure[F]
      }
    }.flatMap {
      _.traverse {
        case (state, effect) =>
          Clock[F].realTime.map { time =>
            (state.copy(statusUpdatedAt = time), effect)
          }
      }
    }

    private def pickMajority[A: Order](proposals: List[A]): Option[A] =
      proposals.foldMap(a => Map(a -> 1)).toList.map(_.swap).maximumOption.map(_._2)

    private def proposalAffinity[A: Order](proposals: List[A], proposal: A): Double =
      if (proposals.nonEmpty)
        proposals.count(Order[A].eqv(proposal, _)).toDouble / proposals.size.toDouble
      else
        0.0

  }

}
