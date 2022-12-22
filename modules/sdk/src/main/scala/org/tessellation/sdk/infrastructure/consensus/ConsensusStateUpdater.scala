package org.tessellation.sdk.infrastructure.consensus

import java.security.KeyPair

import cats._
import cats.data.{NonEmptySet, StateT}
import cats.effect.Async
import cats.syntax.all._

import scala.reflect.runtime.universe.TypeTag

import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.ext.crypto._
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.security.SecurityProvider
import org.tessellation.schema.security.hash.Hash
import org.tessellation.schema.security.signature.Signed
import org.tessellation.schema.security.signature.signature.{Signature, SignatureProof, verifySignatureProof}
import org.tessellation.sdk.domain.consensus.ConsensusFunctions
import org.tessellation.sdk.domain.gossip.Gossip
import org.tessellation.sdk.infrastructure.consensus.declaration._
import org.tessellation.sdk.infrastructure.consensus.declaration.kind.PeerDeclarationKind
import org.tessellation.sdk.infrastructure.consensus.message._
import org.tessellation.sdk.infrastructure.consensus.trigger.{ConsensusTrigger, TimeTrigger}
import org.tessellation.sdk.infrastructure.consensus.update.UnlockConsensusUpdate
import org.tessellation.sdk.infrastructure.metrics.Metrics
import org.tessellation.syntax.sortedCollection._

import eu.timepit.refined.auto._
import io.circe.Encoder
import org.typelevel.log4cats.slf4j.Slf4jLogger

trait ConsensusStateUpdater[F[_], Key, Artifact] {

  type StateUpdateResult = Option[(ConsensusState[Key, Artifact], ConsensusState[Key, Artifact])]

  /** Tries to conditionally update a consensus based on information collected in `resources`, this includes:
    *   - unlocking consensus,
    *   - updating facilitators,
    *   - spreading historical acks,
    *   - advancing consensus status.
    *
    * Returns `Some((oldState, newState))` when the consensus with `key` exists and update was successful, otherwise `None`
    */
  def tryUpdateConsensus(key: Key, resources: ConsensusResources[Artifact]): F[StateUpdateResult]

  /** Tries to lock a consensus if the current status equals to status in the `referenceState`. Returns `Some((unlockedState, lockedState))`
    * when the consensus with `key` exists and was successfully locked, otherwise `None`
    */
  def tryLockConsensus(key: Key, referenceState: ConsensusState[Key, Artifact]): F[StateUpdateResult]

  /** Tries to spread ack if it wasn't already spread. Returns `Some((oldState, newState))` when the consenus with `key` exists and spread
    * was successful, otherwise `None`
    */
  def trySpreadAck(
    key: Key,
    ackKind: PeerDeclarationKind,
    resources: ConsensusResources[Artifact]
  ): F[StateUpdateResult]

}

object ConsensusStateUpdater {

  def make[F[
    _
  ]: Async: KryoSerializer: SecurityProvider: Metrics, Event, Key: Show: Order: TypeTag: Encoder, Artifact <: AnyRef: Eq: Show: TypeTag: Encoder](
    consensusFns: ConsensusFunctions[F, Event, Key, Artifact],
    consensusStorage: ConsensusStorage[F, Event, Key, Artifact],
    facilitatorCalculator: FacilitatorCalculator,
    gossip: Gossip[F],
    keyPair: KeyPair
  ): ConsensusStateUpdater[F, Key, Artifact] = new ConsensusStateUpdater[F, Key, Artifact] {

    private val logger = Slf4jLogger.getLoggerFromClass(ConsensusStateUpdater.getClass)

    private val unlockConsensusFn = UnlockConsensusUpdate.make[F, Key, Artifact]

    def tryLockConsensus(key: Key, referenceState: ConsensusState[Key, Artifact]): F[StateUpdateResult] =
      tryUpdateExistingConsensus(key, lockConsensus(referenceState))

    def trySpreadAck(
      key: Key,
      ackKind: PeerDeclarationKind,
      resources: ConsensusResources[Artifact]
    ): F[StateUpdateResult] =
      tryUpdateExistingConsensus(key, spreadAck(ackKind, resources))

    def tryUpdateConsensus(key: Key, resources: ConsensusResources[Artifact]): F[StateUpdateResult] =
      tryUpdateExistingConsensus(key, updateConsensus(resources))

    import consensusStorage.ModifyStateFn

    private def tryUpdateExistingConsensus(
      key: Key,
      fn: ConsensusState[Key, Artifact] => F[(ConsensusState[Key, Artifact], F[Unit])]
    ): F[StateUpdateResult] =
      consensusStorage
        .condModifyState(key)(toUpdateStateFn(fn))
        .flatMap(evalEffect)
        .flatTap(logIfUpdatedState)

    private def toUpdateStateFn(
      fn: ConsensusState[Key, Artifact] => F[(ConsensusState[Key, Artifact], F[Unit])]
    ): ModifyStateFn[(StateUpdateResult, F[Unit])] = { maybeState =>
      maybeState.flatTraverse { oldState =>
        fn(oldState).map {
          case (newState, effect) =>
            Option.when(newState =!= oldState)((newState.some, ((oldState, newState).some, effect)))
        }
      }
    }

    private def evalEffect(maybeResultAndEffect: Option[(StateUpdateResult, F[Unit])]): F[StateUpdateResult] =
      maybeResultAndEffect.flatTraverse { case (result, effect) => effect.map(_ => result) }

    private def logIfUpdatedState(updateResult: StateUpdateResult): F[Unit] =
      updateResult.traverse {
        case (_, newState) =>
          logger.info(s"State updated ${newState.show}")
      }.void

    private def lockConsensus(
      referenceState: ConsensusState[Key, Artifact]
    )(state: ConsensusState[Key, Artifact]): F[(ConsensusState[Key, Artifact], F[Unit])] =
      if (state.status === referenceState.status && state.lockStatus === Open)
        (state.copy(lockStatus = Closed), Applicative[F].unit).pure[F]
      else
        (state, Applicative[F].unit).pure[F]

    private def spreadAck(
      ackKind: PeerDeclarationKind,
      resources: ConsensusResources[Artifact]
    )(state: ConsensusState[Key, Artifact]): F[(ConsensusState[Key, Artifact], F[Unit])] =
      if (state.spreadAckKinds.contains(ackKind))
        (state, Applicative[F].unit).pure[F]
      else {
        val ack = getAck(ackKind, resources)
        val newState = state.copy(spreadAckKinds = state.spreadAckKinds.incl(ackKind))
        val effect = gossip.spread(ConsensusPeerDeclarationAck(state.key, ackKind, ack))
        (newState, effect).pure[F]
      }

    private def updateConsensus(resources: ConsensusResources[Artifact])(
      state: ConsensusState[Key, Artifact]
    ): F[(ConsensusState[Key, Artifact], F[Unit])] = {
      val stateAndEffect = for {
        _ <- unlockConsensusFn(resources)
        _ <- updateFacilitators(resources)
        effect1 <- spreadHistoricalAck(resources)
        effect2 <- advanceStatus(resources)
      } yield effect1 >> effect2

      stateAndEffect
        .run(state)
    }

    private def updateFacilitators(resources: ConsensusResources[Artifact]): StateT[F, ConsensusState[Key, Artifact], Unit] =
      StateT.modify { state =>
        if (state.locked)
          state
        else
          state.status match {
            case _: CollectingFacilities[Artifact] =>
              state.copy(facilitators =
                facilitatorCalculator.calculate(resources.peerDeclarationsMap, state.facilitators, state.removedFacilitators)
              )
            case _ =>
              state
          }
      }

    private def spreadHistoricalAck(
      resources: ConsensusResources[Artifact]
    ): StateT[F, ConsensusState[Key, Artifact], F[Unit]] =
      StateT { state =>
        resources.ackKinds
          .diff(state.spreadAckKinds)
          .intersect(state.collectedKinds)
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

    private def advanceStatus(resources: ConsensusResources[Artifact]): StateT[F, ConsensusState[Key, Artifact], F[Unit]] =
      StateT { state =>
        if (state.locked)
          (state, Applicative[F].unit).pure[F]
        else {
          state.status match {
            case CollectingFacilities(maybeFacilityInfo) =>
              val maybeFacilities = state.facilitators
                .traverse(resources.peerDeclarationsMap.get)
                .flatMap(_.traverse(_.facility))

              maybeFacilities
                .traverseTap(facilities =>
                  maybeFacilityInfo.traverse(facilityInfo => warnIfForking(facilityInfo.facilitatorsHash)(facilities))
                )
                .flatMap {
                  _.map(_.foldMap(f => (f.upperBound, f.trigger.toList))).flatMap {
                    case (bound, triggers) => pickMajority(triggers).map((bound, _))
                  }.traverse {
                    case (bound, majorityTrigger) =>
                      Applicative[F].whenA(majorityTrigger === TimeTrigger)(consensusStorage.clearTimeTrigger) >>
                        state.facilitators.hashF.flatMap { facilitatorsHash =>
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
                              effect = gossip.spread(ConsensusPeerDeclaration(state.key, Proposal(hash, facilitatorsHash))) *>
                                gossip.spreadCommon(ConsensusArtifact(state.key, artifact))
                            } yield (ProposalInfo[Artifact](artifact, hash).some, facilityInfo.lastSignedArtifact.some, effect)
                          }.map(_.getOrElse((none[ProposalInfo[Artifact]], none[Signed[Artifact]], Applicative[F].unit))).map {
                            case (maybeProposalInfo, maybeLastSignedArtifact, effect) =>
                              val newState =
                                state.copy(status =
                                  CollectingProposals[Artifact](
                                    majorityTrigger,
                                    maybeProposalInfo,
                                    maybeLastSignedArtifact,
                                    facilitatorsHash
                                  )
                                )
                              (newState, effect)
                          }
                        }
                  }
                }
            case CollectingProposals(majorityTrigger, maybeProposalInfo, maybeLastArtifact, ownFacilitatorsHash) =>
              val maybeAllProposals = state.facilitators
                .traverse(resources.peerDeclarationsMap.get)
                .flatMap(_.traverse(declaration => declaration.proposal))

              maybeAllProposals.traverseTap(warnIfForking(ownFacilitatorsHash)).flatMap {
                _.map(_.map(_.hash)).flatTraverse { allProposals =>
                  maybeLastArtifact.map { lastSignedArtifact =>
                    pickValidatedMajority(lastSignedArtifact, majorityTrigger, resources)(allProposals)
                  }
                    .getOrElse(pickMajority(allProposals).pure[F])
                    .flatMap { maybeMajorityHash =>
                      state.facilitators.hashF.flatMap { facilitatorsHash =>
                        maybeMajorityHash.traverse { majorityHash =>
                          val newState =
                            state.copy(status = CollectingSignatures[Artifact](majorityHash, majorityTrigger, facilitatorsHash))
                          val effect = maybeProposalInfo.traverse { proposalInfo =>
                            Signature.fromHash(keyPair.getPrivate, majorityHash).flatMap { signature =>
                              gossip.spread(ConsensusPeerDeclaration(state.key, MajoritySignature(signature, facilitatorsHash)))
                            } >> Metrics[F].recordDistribution(
                              "dag_consensus_proposal_affinity",
                              proposalAffinity(allProposals, proposalInfo.proposalArtifactHash)
                            )
                          }.void
                          (newState, effect).pure[F]
                        }
                      }
                    }
                }
              }

            case CollectingSignatures(majorityHash, majorityTrigger, ownFacilitatorsHash) =>
              val maybeAllSignatures =
                state.facilitators.sorted.traverse { peerId =>
                  resources.peerDeclarationsMap
                    .get(peerId)
                    .flatMap(peerDeclaration => peerDeclaration.signature.map(signature => (peerId, signature)))
                }

              maybeAllSignatures.traverseTap(signatures => warnIfForking(ownFacilitatorsHash)(signatures.unzip._2)).flatMap {
                _.map(_.map { case (id, signature) => SignatureProof(PeerId._Id.get(id), signature.signature) }.toList).traverse {
                  allSignatures =>
                    allSignatures
                      .filterA(verifySignatureProof(majorityHash, _))
                      .flatTap { validSignatures =>
                        logger
                          .warn(
                            s"Removed ${(allSignatures.size - validSignatures.size).show} invalid signatures during consensus for key ${state.key.show}, " +
                              s"${validSignatures.size.show} valid signatures left"
                          )
                          .whenA(allSignatures.size =!= validSignatures.size)
                      }
                }.flatMap { maybeOnlyValidSignatures =>
                  state.facilitators.hashF.map { facilitatorsHash =>
                    for {
                      validSignatures <- maybeOnlyValidSignatures
                      validSignaturesNel <- NonEmptySet.fromSet(validSignatures.toSortedSet)
                      majorityArtifact <- resources.artifacts.get(majorityHash)
                      signedArtifact = Signed(majorityArtifact, validSignaturesNel)
                      newState = state.copy(status = Finished[Artifact](signedArtifact, majorityTrigger, facilitatorsHash))
                      effect = consensusFns.consumeSignedMajorityArtifact(signedArtifact)
                    } yield (newState, effect)
                  }
                }
              }
            case Finished(_, _, _) =>
              none[(ConsensusState[Key, Artifact], F[Unit])].pure[F]
          }
        }.map { maybeStateAndEffect =>
          maybeStateAndEffect.map { case (state, effect) => (state.copy(lockStatus = Open), effect) }
            .getOrElse((state, Applicative[F].unit))
        }
      }

    private def getAck(ackKind: PeerDeclarationKind, resources: ConsensusResources[Artifact]): Set[PeerId] = {
      def ackFor[B <: PeerDeclaration](getter: PeerDeclarations => Option[B]): Set[PeerId] =
        resources.peerDeclarationsMap.view.filter { case (_, peerDeclarations) => getter(peerDeclarations).isDefined }.keys.toSet

      ackKind match {
        case kind.Facility          => ackFor(_.facility)
        case kind.Proposal          => ackFor(_.proposal)
        case kind.MajoritySignature => ackFor(_.signature)
      }
    }

    private def warnIfForking(ownFacilitatorsHash: Hash)(
      declarations: List[PeerDeclaration]
    ): F[Unit] =
      pickMajority(declarations.map(_.facilitatorsHash)).traverse { majorityFacilitatorsHash =>
        logger
          .warn(s"Different facilitators hashes. This node is in fork")
          .whenA(majorityFacilitatorsHash =!= ownFacilitatorsHash)
      }.void

    private def pickMajority[A: Order](proposals: List[A]): Option[A] =
      proposals.foldMap(a => Map(a -> 1)).toList.map(_.swap).maximumOption.map(_._2)

    private def pickValidatedMajority(
      lastSignedArtifact: Signed[Artifact],
      trigger: ConsensusTrigger,
      resources: ConsensusResources[Artifact]
    )(proposals: List[Hash]): F[Option[Hash]] = {
      val sortedProposals = proposals.foldMap(a => Map(a -> 1)).toList.map(_.swap).sorted.reverse

      def go(proposals: List[(Int, Hash)]): F[Option[Hash]] =
        proposals match {
          case (occurrences, majorityHash) :: tail =>
            resources.artifacts
              .get(majorityHash)
              .traverse { artifact =>
                consensusFns.validateArtifact(lastSignedArtifact, trigger)(artifact)
              }
              .flatMap {
                _.flatTraverse {
                  _.fold(
                    cause =>
                      logger.warn(cause)(s"Found invalid majority hash=${majorityHash.show} with occurrences=$occurrences") >> go(tail),
                    _ => majorityHash.some.pure[F]
                  )
                }
              }
          case Nil => none[Hash].pure[F]
        }

      go(sortedProposals)
    }

    private def proposalAffinity[A: Order](proposals: List[A], proposal: A): Double =
      if (proposals.nonEmpty)
        proposals.count(Order[A].eqv(proposal, _)).toDouble / proposals.size.toDouble
      else
        0.0

  }

}
