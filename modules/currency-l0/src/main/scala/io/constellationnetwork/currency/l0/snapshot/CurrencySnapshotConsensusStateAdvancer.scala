package io.constellationnetwork.currency.l0.snapshot

import java.security.KeyPair

import cats.Applicative
import cats.data.{NonEmptySet, StateT}
import cats.effect.{Async, Clock}
import cats.syntax.all._

import scala.collection.immutable.SortedMap
import scala.concurrent.duration.FiniteDuration

import io.constellationnetwork.currency.dataApplication.BaseDataApplicationL0Service
import io.constellationnetwork.currency.l0.snapshot.schema._
import io.constellationnetwork.currency.l0.snapshot.services.StateChannelSnapshotService
import io.constellationnetwork.currency.schema.currency.CurrencySnapshotContext
import io.constellationnetwork.ext.collection.FoldableOps.pickMajority
import io.constellationnetwork.ext.crypto._
import io.constellationnetwork.node.shared.config.types.ConsensusConfig
import io.constellationnetwork.node.shared.domain.cluster.storage.ClusterStorage
import io.constellationnetwork.node.shared.domain.gossip.Gossip
import io.constellationnetwork.node.shared.domain.node.NodeStorage
import io.constellationnetwork.node.shared.infrastructure.consensus.ConsensusStateUpdater._
import io.constellationnetwork.node.shared.infrastructure.consensus._
import io.constellationnetwork.node.shared.infrastructure.consensus.declaration._
import io.constellationnetwork.node.shared.infrastructure.consensus.message._
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.{ConsensusTrigger, TimeTrigger}
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.node.shared.infrastructure.node.RestartService
import io.constellationnetwork.node.shared.infrastructure.snapshot.SnapshotConsensusFunctions.gossipForkInfo
import io.constellationnetwork.node.shared.snapshot.currency._
import io.constellationnetwork.schema.currencyMessage.fetchStakingAddress
import io.constellationnetwork.schema.gossip.Ordinal
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, SnapshotOrdinal}
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.signature._
import io.constellationnetwork.statechannel.StateChannelSnapshotBinary
import io.constellationnetwork.syntax.sortedCollection.sortedSetSyntax

import eu.timepit.refined.auto._
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

abstract class CurrencySnapshotConsensusStateAdvancer[F[_]]
    extends ConsensusStateAdvancer[
      F,
      CurrencySnapshotKey,
      CurrencySnapshotArtifact,
      CurrencySnapshotContext,
      CurrencySnapshotStatus,
      CurrencyConsensusOutcome,
      CurrencyConsensusKind
    ] {}

object CurrencySnapshotConsensusStateAdvancer {

  def make[F[_]: Async: SecurityProvider: Metrics: HasherSelector](
    consensusConfig: ConsensusConfig,
    keyPair: KeyPair,
    consensusStorage: CurrencyConsensusStorage[F],
    consensusFns: CurrencySnapshotConsensusFunctions[F],
    stateChannelSnapshotService: StateChannelSnapshotService[F],
    gossip: Gossip[F],
    maybeDataApplication: Option[BaseDataApplicationL0Service[F]],
    restartService: RestartService[F, _],
    nodeStorage: NodeStorage[F],
    leavingDelay: FiniteDuration,
    getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]],
    clusterStorageInstance: ClusterStorage[F]
  ): CurrencySnapshotConsensusStateAdvancer[F] =
    new CurrencySnapshotConsensusStateAdvancer[F] {

      val logger: SelfAwareStructuredLogger[F] = Slf4jLogger.getLoggerFromClass[F](CurrencySnapshotConsensusStateAdvancer.getClass)
      val facilitatorsObservationName = "facilitators"

      protected val clusterStorage: ClusterStorage[F] = clusterStorageInstance
      protected val config: ConsensusConfig = consensusConfig

      case class StateTransition(
        newState: CurrencySnapshotConsensusState,
        sideEffect: F[Unit]
      )

      private def shouldTimeout(
        state: CurrencySnapshotConsensusState,
        resources: ConsensusResources[CurrencySnapshotArtifact, CurrencyConsensusKind],
        now: FiniteDuration
      ): Boolean = {
        val elapsedSinceStateCreated = now - state.createdAt
        val elapsedSinceLastUpdate = now - resources.updatedAt

        elapsedSinceStateCreated > config.peersDeclarationTimeout &&
        elapsedSinceLastUpdate > config.peersDeclarationTimeout
      }

      def getConsensusOutcome(
        state: CurrencySnapshotConsensusState
      ): Option[(Previous[CurrencySnapshotKey], CurrencyConsensusOutcome)] =
        state.status match {
          case f @ Finished(_, _, _, _, _, _) =>
            val outcome = CurrencyConsensusOutcome(
              state.key,
              state.facilitators,
              state.removedFacilitators,
              state.withdrawnFacilitators,
              f
            )
            (Previous(state.lastOutcome.key), outcome).some
          case _ => None
        }

      def advanceStatus(
        resources: ConsensusResources[CurrencySnapshotArtifact, CurrencyConsensusKind]
      ): StateT[F, CurrencySnapshotConsensusState, F[Unit]] =
        StateT[F, CurrencySnapshotConsensusState, F[Unit]] { state =>
          HasherSelector[F].withCurrent { implicit hasher =>
            processStateAdvancement(state, resources)
          }
        }

      private def processStateAdvancement(
        state: CurrencySnapshotConsensusState,
        resources: ConsensusResources[CurrencySnapshotArtifact, CurrencyConsensusKind]
      )(implicit hasher: Hasher[F]): F[(CurrencySnapshotConsensusState, F[Unit])] =
        if (state.lockStatus === LockStatus.Closed) {
          returnStateUnchanged(state)
        } else {
          attemptStateTransition(state, resources).map {
            case Some(transition) =>
              (transition.newState.copy(lockStatus = LockStatus.Open), transition.sideEffect)
            case None =>
              (state, Applicative[F].unit)
          }
        }

      private def returnStateUnchanged(state: CurrencySnapshotConsensusState): F[(CurrencySnapshotConsensusState, F[Unit])] =
        (state, Applicative[F].unit).pure[F]

      private def attemptStateTransition(
        state: CurrencySnapshotConsensusState,
        resources: ConsensusResources[CurrencySnapshotArtifact, CurrencyConsensusKind]
      )(implicit hasher: Hasher[F]): F[Option[StateTransition]] =
        state.status match {
          case s: CollectingFacilities =>
            handleCollectingFacilities(state, s, resources)
          case s: CollectingProposals =>
            handleCollectingProposals(state, s, resources)
          case s: CollectingSignatures =>
            handleCollectingSignatures(state, s, resources)
          case s: CollectingBinarySignatures =>
            handleCollectingBinarySignatures(state, s, resources)
          case _: Finished =>
            none[StateTransition].pure[F]
        }

      private def handleCollectingFacilities(
        state: CurrencySnapshotConsensusState,
        status: CollectingFacilities,
        resources: ConsensusResources[CurrencySnapshotArtifact, CurrencyConsensusKind]
      ): F[Option[StateTransition]] =
        for {
          now <- Clock[F].monotonic
          isTimeout = shouldTimeout(state, resources, now)
          elapsed = now - resources.updatedAt

          maybeFacilities <-
            if (isTimeout) {
              getPartialDeclarations(state, resources, elapsed, "Facilities")(_.facility)
            } else {
              maybeGetAllDeclarations(state, resources)(_.facility)
            }

          _ <- maybeFacilities.traverse_(facilities => checkForForkingFacilitators(facilities, status.facilitatorsHash))

          result <- maybeFacilities.flatTraverse { facilities =>
            if (isTimeout) {
              val respondedPeers = facilities.keySet
              removeMissingFacilitators(state, respondedPeers, "Facilities").flatMap {
                case (updatedState, _) =>
                  processFacilitiesData(updatedState, facilities)
              }
            } else {
              processFacilitiesData(state, facilities)
            }
          }
        } yield result

      private def checkForForkingFacilitators(
        facilities: SortedMap[PeerId, Facility],
        ownHash: Hash
      ): F[Unit] =
        recoverIfForking[F](
          ownHash,
          facilitatorsObservationName,
          restartService,
          nodeStorage,
          leavingDelay
        )(facilities.map { case (peer, facility) => (peer, facility.facilitatorsHash) })

      private def processFacilitiesData(
        state: CurrencySnapshotConsensusState,
        facilities: SortedMap[PeerId, Facility]
      ): F[Option[StateTransition]] = {
        val aggregated = facilities.foldMap(f => (f.upperBound, f.candidates.value, f.trigger.toList))
        val (bound, candidates, triggers) = aggregated

        pickMajority(triggers).flatTraverse { majorityTrigger =>
          transitionToProposals(state, bound, candidates, majorityTrigger).map(_.some)
        }
      }

      private def transitionToProposals(
        state: CurrencySnapshotConsensusState,
        bound: Bound,
        candidates: Set[PeerId],
        majorityTrigger: ConsensusTrigger
      ): F[StateTransition] =
        HasherSelector[F].withCurrent { implicit hasher =>
          for {
            _ <- clearTimeTriggerIfNeeded(majorityTrigger)
            facilitatorsHash <- computeFacilitatorsHash(state)

            peerEvents <- consensusStorage.pullEvents(bound)
            events = extractEvents(peerEvents)

            proposalData <- createProposalArtifact(state, majorityTrigger, events)
            (artifact, context, returnedEvents) = proposalData

            _ <- storeReturnedEvents(peerEvents, returnedEvents)
            hash <- hashArtifact(artifact)

            sideEffect = spreadProposal(state, hash, facilitatorsHash, artifact)
            newState = buildProposalsState(state, majorityTrigger, artifact, context, hash, candidates, facilitatorsHash)

          } yield StateTransition(newState, sideEffect)
        }

      private def clearTimeTriggerIfNeeded(trigger: ConsensusTrigger): F[Unit] =
        Applicative[F].whenA(trigger === TimeTrigger)(consensusStorage.clearTimeTrigger)

      private def computeFacilitatorsHash(state: CurrencySnapshotConsensusState): F[Hash] =
        HasherSelector[F].withCurrent(implicit hasher => state.facilitators.value.hash)

      private def extractEvents(peerEvents: Map[PeerId, List[(Ordinal, CurrencySnapshotEvent)]]): Set[CurrencySnapshotEvent] =
        peerEvents.toList.flatMap(_._2).map(_._2).toSet

      private def createProposalArtifact(
        state: CurrencySnapshotConsensusState,
        majorityTrigger: ConsensusTrigger,
        events: Set[CurrencySnapshotEvent]
      )(implicit hasher: Hasher[F]): F[(CurrencySnapshotArtifact, CurrencySnapshotContext, Set[CurrencySnapshotEvent])] =
        consensusFns.createProposalArtifact(
          state.key,
          state.lastOutcome.finished.signedMajorityArtifact,
          state.lastOutcome.finished.context,
          hasher,
          majorityTrigger,
          events,
          state.facilitators.value.toSet,
          getGlobalSnapshotByOrdinal
        )

      private def storeReturnedEvents(
        peerEvents: Map[PeerId, List[(Ordinal, CurrencySnapshotEvent)]],
        returnedEvents: Set[CurrencySnapshotEvent]
      ): F[Unit] = {
        val returnedPeerEvents = peerEvents.map {
          case (peerId, events) =>
            (peerId, events.filter { case (_, event) => returnedEvents.contains(event) })
        }.filter { case (_, events) => events.nonEmpty }

        consensusStorage.addEvents(returnedPeerEvents)
      }

      private def hashArtifact(artifact: CurrencySnapshotArtifact): F[Hash] =
        HasherSelector[F].withCurrent(implicit hasher => artifact.hash)

      private def spreadProposal(
        state: CurrencySnapshotConsensusState,
        hash: Hash,
        facilitatorsHash: Hash,
        artifact: CurrencySnapshotArtifact
      ): F[Unit] =
        gossip.spread(ConsensusPeerDeclaration(state.key, Proposal(hash, facilitatorsHash))) *>
          gossip.spreadCommon(ConsensusArtifact(state.key, artifact))

      private def buildProposalsState(
        state: CurrencySnapshotConsensusState,
        majorityTrigger: ConsensusTrigger,
        artifact: CurrencySnapshotArtifact,
        context: CurrencySnapshotContext,
        hash: Hash,
        candidates: Set[PeerId],
        facilitatorsHash: Hash
      ): CurrencySnapshotConsensusState =
        state.copy(
          status = CollectingProposals(
            majorityTrigger,
            ArtifactInfo(artifact, context, hash),
            Candidates(candidates),
            facilitatorsHash
          )
        )

      private def handleCollectingProposals(
        state: CurrencySnapshotConsensusState,
        status: CollectingProposals,
        resources: ConsensusResources[CurrencySnapshotArtifact, CurrencyConsensusKind]
      ): F[Option[StateTransition]] =
        HasherSelector[F].withCurrent { implicit hasher =>
          for {
            now <- Clock[F].monotonic
            isTimeout = shouldTimeout(state, resources, now)
            elapsed = now - resources.updatedAt

            maybeAllProposals <-
              if (isTimeout) {
                getPartialDeclarations(state, resources, elapsed, "Proposals")(_.proposal)
              } else {
                maybeGetAllDeclarations(state, resources)(_.proposal)
              }

            _ <- maybeAllProposals.traverse_(proposals => checkForForkingProposals(proposals, status.facilitatorsHash))

            result <- maybeAllProposals.flatTraverse { proposals =>
              if (isTimeout) {
                val respondedPeers = proposals.keySet
                removeMissingFacilitators(state, respondedPeers, "Proposals").flatMap {
                  case (updatedState, _) =>
                    processProposalsData(updatedState, status, resources, proposals)
                }
              } else {
                processProposalsData(state, status, resources, proposals)
              }
            }
          } yield result
        }

      private def checkForForkingProposals(
        proposals: SortedMap[PeerId, Proposal],
        ownHash: Hash
      ): F[Unit] =
        recoverIfForking[F](
          ownHash,
          facilitatorsObservationName,
          restartService,
          nodeStorage,
          leavingDelay
        )(proposals.map { case (peerId, proposal) => (peerId, proposal.facilitatorsHash) })

      private def processProposalsData(
        state: CurrencySnapshotConsensusState,
        status: CollectingProposals,
        resources: ConsensusResources[CurrencySnapshotArtifact, CurrencyConsensusKind],
        proposals: SortedMap[PeerId, Proposal]
      )(implicit hasher: Hasher[F]): F[Option[StateTransition]] = {
        val allProposalHashes = proposals.values.toList.map(_.hash)

        findMajorityArtifact(state, status, resources, allProposalHashes).flatMap {
          case Some(majorityInfo) =>
            transitionToSignatures(state, status, majorityInfo, allProposalHashes).map(_.some)
          case None =>
            none[StateTransition].pure[F]
        }
      }

      private def findMajorityArtifact(
        state: CurrencySnapshotConsensusState,
        status: CollectingProposals,
        resources: ConsensusResources[CurrencySnapshotArtifact, CurrencyConsensusKind],
        allProposalHashes: List[Hash]
      )(implicit hasher: Hasher[F]): F[Option[ArtifactInfo[CurrencySnapshotArtifact, CurrencySnapshotContext]]] =
        pickValidatedMajorityArtifact(
          status.proposalArtifactInfo,
          state.lastOutcome.finished.signedMajorityArtifact,
          state.lastOutcome.finished.context,
          status.majorityTrigger,
          resources,
          allProposalHashes,
          state.facilitators.value.toSet,
          consensusFns,
          getGlobalSnapshotByOrdinal
        )

      private def transitionToSignatures(
        state: CurrencySnapshotConsensusState,
        status: CollectingProposals,
        majorityArtifactInfo: ArtifactInfo[CurrencySnapshotArtifact, CurrencySnapshotContext],
        allProposalHashes: List[Hash]
      )(implicit hasher: Hasher[F]): F[StateTransition] =
        for {
          facilitatorsHash <- state.facilitators.value.hash
          signature <- Signature.fromHash(keyPair.getPrivate, majorityArtifactInfo.hash)

          _ <- recordProposalAffinity(allProposalHashes, status.proposalArtifactInfo.hash)

          sideEffect = spreadMajoritySignature(state, signature, facilitatorsHash)
          newState = buildSignaturesState(state, status, majorityArtifactInfo, facilitatorsHash)

        } yield StateTransition(newState, sideEffect)

      private def recordProposalAffinity(allHashes: List[Hash], ownHash: Hash): F[Unit] =
        Metrics[F].recordDistribution("dag_consensus_proposal_affinity", proposalAffinity(allHashes, ownHash))

      private def spreadMajoritySignature(
        state: CurrencySnapshotConsensusState,
        signature: Signature,
        facilitatorsHash: Hash
      ): F[Unit] =
        gossip.spread(ConsensusPeerDeclaration(state.key, MajoritySignature(signature, facilitatorsHash)))

      private def buildSignaturesState(
        state: CurrencySnapshotConsensusState,
        status: CollectingProposals,
        majorityArtifactInfo: ArtifactInfo[CurrencySnapshotArtifact, CurrencySnapshotContext],
        facilitatorsHash: Hash
      ): CurrencySnapshotConsensusState =
        state.copy(
          status = CollectingSignatures(
            majorityArtifactInfo,
            status.majorityTrigger,
            status.candidates,
            facilitatorsHash
          )
        )

      private def handleCollectingSignatures(
        state: CurrencySnapshotConsensusState,
        status: CollectingSignatures,
        resources: ConsensusResources[CurrencySnapshotArtifact, CurrencyConsensusKind]
      )(implicit hasher: Hasher[F]): F[Option[StateTransition]] =
        for {
          now <- Clock[F].monotonic
          isTimeout = shouldTimeout(state, resources, now)
          elapsed = now - resources.updatedAt

          maybeAllSignatures <-
            if (isTimeout) {
              getPartialDeclarations(state, resources, elapsed, "Signatures")(_.signature)
            } else {
              maybeGetAllDeclarations(state, resources)(_.signature)
            }

          maybeAllFacilities <- maybeGetAllDeclarations(state, resources)(_.facility)

          _ <- maybeAllSignatures.traverse_(signatures => checkForForkingSignatures(signatures, status.facilitatorsHash))

          maybeGlobalSnapshotOrdinal = extractGlobalSnapshotOrdinal(maybeAllFacilities)

          result <- maybeGlobalSnapshotOrdinal.flatTraverse { globalSnapshotOrdinal =>
            maybeAllSignatures.flatTraverse { signatures =>
              if (isTimeout) {
                val respondedPeers = signatures.keySet
                removeMissingFacilitators(state, respondedPeers, "Signatures").flatMap {
                  case (updatedState, _) =>
                    processSignaturesData(updatedState, status, signatures, globalSnapshotOrdinal)
                }
              } else {
                processSignaturesData(state, status, signatures, globalSnapshotOrdinal)
              }
            }
          }
        } yield result

      private def checkForForkingSignatures(
        signatures: SortedMap[PeerId, MajoritySignature],
        ownHash: Hash
      ): F[Unit] =
        recoverIfForking[F](
          ownHash,
          facilitatorsObservationName,
          restartService,
          nodeStorage,
          leavingDelay
        )(signatures.map { case (peerId, sig) => (peerId, sig.facilitatorsHash) })

      private def extractGlobalSnapshotOrdinal(
        maybeAllFacilities: Option[SortedMap[PeerId, Facility]]
      ): Option[SnapshotOrdinal] =
        maybeAllFacilities
          .map(_.map { case (_, f) => f.lastGlobalSnapshotOrdinal })
          .map(_.toList)
          .flatMap(pickMajority(_))

      private def processSignaturesData(
        state: CurrencySnapshotConsensusState,
        status: CollectingSignatures,
        signatures: SortedMap[PeerId, MajoritySignature],
        globalSnapshotOrdinal: SnapshotOrdinal
      )(implicit hasher: Hasher[F]): F[Option[StateTransition]] = {
        val allSignatureProofs = signatures.map {
          case (id, sig) =>
            SignatureProof(PeerId._Id.get(id), sig.signature)
        }.toList

        validateAndCreateBinary(state, status, allSignatureProofs, globalSnapshotOrdinal)
      }

      private def validateAndCreateBinary(
        state: CurrencySnapshotConsensusState,
        status: CollectingSignatures,
        allSignatures: List[SignatureProof],
        globalSnapshotOrdinal: SnapshotOrdinal
      )(implicit hasher: Hasher[F]): F[Option[StateTransition]] =
        allSignatures
          .filterA(sig => verifySignatureProof(status.majorityArtifactInfo.hash, sig))
          .flatTap(validSignatures => logInvalidSignaturesIfAny(state, allSignatures.size, validSignatures.size))
          .flatMap(validSignatures => transitionToBinarySignatures(state, status, validSignatures, globalSnapshotOrdinal))

      private def logInvalidSignaturesIfAny(
        state: CurrencySnapshotConsensusState,
        totalCount: Int,
        validCount: Int
      ): F[Unit] =
        logger
          .warn(
            s"Removed ${(totalCount - validCount).show} invalid signatures during consensus for key ${state.key.show}, " +
              s"${validCount.show} valid signatures left"
          )
          .whenA(totalCount =!= validCount)

      private def transitionToBinarySignatures(
        state: CurrencySnapshotConsensusState,
        status: CollectingSignatures,
        validSignatures: List[SignatureProof],
        globalSnapshotOrdinal: SnapshotOrdinal
      )(implicit hasher: Hasher[F]): F[Option[StateTransition]] =
        HasherSelector[F].withCurrent { implicit hasher =>
          state.facilitators.value.hash
        }.flatMap { facilitatorsHash =>
          NonEmptySet.fromSet(validSignatures.toSortedSet).traverse { validSignaturesNes =>
            buildBinaryTransition(state, status, validSignaturesNes, facilitatorsHash, globalSnapshotOrdinal)
          }
        }

      private def buildBinaryTransition(
        state: CurrencySnapshotConsensusState,
        status: CollectingSignatures,
        signatures: NonEmptySet[SignatureProof],
        facilitatorsHash: Hash,
        globalSnapshotOrdinal: SnapshotOrdinal
      )(implicit hasher: Hasher[F]): F[StateTransition] = {
        val signedArtifact = Signed(status.majorityArtifactInfo.artifact, signatures)
        val maybeStakingAddress = fetchStakingAddress(state.lastOutcome.finished.context.snapshotInfo)

        stateChannelSnapshotService
          .createBinary(
            signedArtifact,
            state.lastOutcome.finished.binaryArtifactHash,
            globalSnapshotOrdinal.some,
            maybeStakingAddress
          )
          .map { signedBinary =>
            val collectingBinarySignaturesStatus: CurrencyConsensusStep = CollectingBinarySignatures(
              signedArtifact,
              status.majorityArtifactInfo.context,
              signedBinary.value,
              status.majorityTrigger,
              status.candidates,
              facilitatorsHash
            )

            val newState = state.copy(status = collectingBinarySignaturesStatus)

            val sideEffect = gossip.spread(
              ConsensusPeerDeclaration(
                state.key,
                BinarySignature(signedBinary.proofs.head.signature, facilitatorsHash)
              )
            )

            StateTransition(newState, sideEffect)
          }
      }

      private def handleCollectingBinarySignatures(
        state: CurrencySnapshotConsensusState,
        status: CollectingBinarySignatures,
        resources: ConsensusResources[CurrencySnapshotArtifact, CurrencyConsensusKind]
      ): F[Option[StateTransition]] =
        for {
          now <- Clock[F].monotonic
          isTimeout = shouldTimeout(state, resources, now)
          elapsed = now - resources.updatedAt

          maybeAllBinarySignatures <-
            if (isTimeout) {
              getPartialDeclarations(state, resources, elapsed, "BinarySignatures")(_.binarySignature)
            } else {
              maybeGetAllDeclarations(state, resources)(_.binarySignature)
            }

          _ <- maybeAllBinarySignatures.traverse_(signatures => checkForForkingBinarySignatures(signatures, status.facilitatorsHash))

          result <- maybeAllBinarySignatures.flatTraverse { signatures =>
            if (isTimeout) {
              val respondedPeers = signatures.keySet
              removeMissingFacilitators(state, respondedPeers, "BinarySignatures").flatMap {
                case (updatedState, _) =>
                  processBinarySignaturesData(updatedState, status, signatures)
              }
            } else {
              processBinarySignaturesData(state, status, signatures)
            }
          }
        } yield result

      private def checkForForkingBinarySignatures(
        signatures: SortedMap[PeerId, BinarySignature],
        ownHash: Hash
      ): F[Unit] =
        recoverIfForking[F](
          ownHash,
          facilitatorsObservationName,
          restartService,
          nodeStorage,
          leavingDelay
        )(signatures.map { case (peerId, sig) => (peerId, sig.facilitatorsHash) })

      private def processBinarySignaturesData(
        state: CurrencySnapshotConsensusState,
        status: CollectingBinarySignatures,
        signatures: SortedMap[PeerId, BinarySignature]
      ): F[Option[StateTransition]] = {
        val allSignatureProofs = signatures.map {
          case (id, bs) =>
            SignatureProof(PeerId._Id.get(id), bs.signature)
        }.toList

        validateAndFinalize(state, status, allSignatureProofs)
      }

      private def validateAndFinalize(
        state: CurrencySnapshotConsensusState,
        status: CollectingBinarySignatures,
        allSignatures: List[SignatureProof]
      ): F[Option[StateTransition]] =
        HasherSelector[F].withCurrent { implicit hasher =>
          for {
            binaryHash <- status.binary.hash
            validSignatures <- allSignatures.filterA(sig => verifySignatureProof(binaryHash, sig))

            _ <- logInvalidBinarySignaturesIfAny(state, allSignatures.size, validSignatures.size)

            result <- transitionToFinished(state, status, validSignatures)
          } yield result
        }

      private def logInvalidBinarySignaturesIfAny(
        state: CurrencySnapshotConsensusState,
        totalCount: Int,
        validCount: Int
      ): F[Unit] =
        logger
          .warn(
            s"Removed ${(totalCount - validCount).show} invalid binary signatures during consensus for key ${state.key.show}, " +
              s"${validCount.show} valid signatures left"
          )
          .whenA(totalCount =!= validCount)

      private def transitionToFinished(
        state: CurrencySnapshotConsensusState,
        status: CollectingBinarySignatures,
        validSignatures: List[SignatureProof]
      )(implicit hasher: Hasher[F]): F[Option[StateTransition]] =
        HasherSelector[F].withCurrent { implicit hasher =>
          state.facilitators.value.hash
        }.flatMap { facilitatorsHash =>
          NonEmptySet.fromSet(validSignatures.toSortedSet).traverse { validSignaturesNes =>
            buildFinishedTransition(state, status, validSignaturesNes, facilitatorsHash)
          }
        }

      private def buildFinishedTransition(
        state: CurrencySnapshotConsensusState,
        status: CollectingBinarySignatures,
        signatures: NonEmptySet[SignatureProof],
        facilitatorsHash: Hash
      )(implicit hasher: Hasher[F]): F[StateTransition] = {
        val finalSignedBinary = Signed(status.binary, signatures)

        HasherSelector[F].withCurrent { implicit hasher =>
          finalSignedBinary.toHashed
        }.map { hashedBinary =>
          val finishedStatus: CurrencyConsensusStep = Finished(
            status.signedMajorityArtifact,
            hashedBinary.hash,
            status.context,
            status.majorityTrigger,
            status.candidates,
            facilitatorsHash
          )

          val newState = state.copy(status = finishedStatus)

          val sideEffect = persistSnapshotAndGossip(
            status.signedMajorityArtifact,
            hashedBinary,
            state,
            status.context
          )

          StateTransition(newState, sideEffect)
        }
      }

      private def persistSnapshotAndGossip(
        signedArtifact: Signed[CurrencySnapshotArtifact],
        hashedBinary: Hashed[StateChannelSnapshotBinary],
        state: CurrencySnapshotConsensusState,
        context: CurrencySnapshotContext
      )(implicit hasher: Hasher[F]): F[Unit] =
        stateChannelSnapshotService.consume(
          signedArtifact,
          hashedBinary,
          state.lastOutcome.facilitators.value,
          context
        ) >>
          gossipForkInfo(gossip, signedArtifact) >>
          maybeDataApplication.traverse_ { da =>
            HasherSelector[F].withCurrent { implicit hasher =>
              signedArtifact.toHashed
            } >>= da.onSnapshotConsensusResult
          }.handleErrorWith(logger.error(_)("Unhandled exception during onSnapshotConsensusResult"))
    }
}
