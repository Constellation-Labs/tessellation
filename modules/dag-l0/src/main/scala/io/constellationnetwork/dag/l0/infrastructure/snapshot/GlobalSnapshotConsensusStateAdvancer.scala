package io.constellationnetwork.dag.l0.infrastructure.snapshot

import java.security.KeyPair

import cats.data.{NonEmptySet, StateT}
import cats.effect.{Async, Clock}
import cats.syntax.all._
import cats.{Applicative, MonadThrow}

import scala.collection.immutable.SortedMap
import scala.concurrent.duration.FiniteDuration

import io.constellationnetwork.dag.l0.infrastructure.snapshot.event.GlobalSnapshotEvent
import io.constellationnetwork.dag.l0.infrastructure.snapshot.schema._
import io.constellationnetwork.ext.collection.FoldableOps.pickMajority
import io.constellationnetwork.ext.crypto._
import io.constellationnetwork.node.shared.config.types.ConsensusConfig
import io.constellationnetwork.node.shared.domain.cluster.storage.ClusterStorage
import io.constellationnetwork.node.shared.domain.gossip.Gossip
import io.constellationnetwork.node.shared.domain.node.NodeStorage
import io.constellationnetwork.node.shared.domain.snapshot.storage.{LastNGlobalSnapshotStorage, LastSnapshotStorage, SnapshotStorage}
import io.constellationnetwork.node.shared.infrastructure.consensus._
import io.constellationnetwork.node.shared.infrastructure.consensus.declaration._
import io.constellationnetwork.node.shared.infrastructure.consensus.message._
import io.constellationnetwork.node.shared.infrastructure.consensus.state.ConsensusStateUpdater._
import io.constellationnetwork.node.shared.infrastructure.consensus.state._
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.{ConsensusTrigger, TimeTrigger}
import io.constellationnetwork.node.shared.infrastructure.fork.ExitOnFork
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.node.shared.infrastructure.node.RestartService
import io.constellationnetwork.node.shared.infrastructure.snapshot.SnapshotConsensusFunctions.gossipForkInfo
import io.constellationnetwork.schema.gossip.Ordinal
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo, SnapshotOrdinal}
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.signature._
import io.constellationnetwork.syntax.sortedCollection._

import eu.timepit.refined.auto._
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

abstract class GlobalSnapshotConsensusStateAdvancer[F[_]]
    extends ConsensusStateAdvancer[
      F,
      GlobalSnapshotKey,
      GlobalSnapshotArtifact,
      GlobalSnapshotContext,
      GlobalSnapshotStatus,
      GlobalConsensusOutcome,
      GlobalConsensusKind
    ] {}

object GlobalSnapshotConsensusStateAdvancer {
  def make[F[_]: Async: SecurityProvider: Metrics: HasherSelector](
    consensusConfig: ConsensusConfig,
    keyPair: KeyPair,
    consensusStorage: GlobalConsensusStorage[F],
    globalSnapshotStorage: SnapshotStorage[F, GlobalSnapshotArtifact, GlobalSnapshotContext],
    consensusFns: GlobalSnapshotConsensusFunctions[F],
    gossip: Gossip[F],
    restartService: RestartService[F, _],
    nodeStorage: NodeStorage[F],
    leavingDelay: FiniteDuration,
    lastNGlobalSnapshotStorage: LastNGlobalSnapshotStorage[F],
    lastGlobalSnapshotStorage: LastSnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo],
    getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]],
    clusterStorageInstance: ClusterStorage[F]
  ): GlobalSnapshotConsensusStateAdvancer[F] = new GlobalSnapshotConsensusStateAdvancer[F] {

    val logger: SelfAwareStructuredLogger[F] = Slf4jLogger.getLoggerFromClass[F](GlobalSnapshotConsensusStateAdvancer.getClass)
    val facilitatorsObservationName = "facilitators"

    protected val clusterStorage: ClusterStorage[F] = clusterStorageInstance
    protected val config: ConsensusConfig = consensusConfig

    case class StateTransition(
      newState: GlobalSnapshotConsensusState,
      sideEffect: F[Unit]
    )

    def getConsensusOutcome(
      state: GlobalSnapshotConsensusState
    ): Option[(Previous[GlobalSnapshotKey], GlobalConsensusOutcome)] =
      state.status match {
        case f @ Finished(_, _, _, _, _) =>
          val outcome = GlobalConsensusOutcome(
            state.key,
            state.facilitators,
            state.removedFacilitators,
            state.withdrawnFacilitators,
            Finished(f.signedMajorityArtifact, f.context, f.majorityTrigger, f.candidates, f.facilitatorsHash)
          )
          (Previous(state.lastOutcome.key), outcome).some
        case _ => None
      }

    def advanceStatus(
      resources: ConsensusResources[GlobalSnapshotArtifact, GlobalConsensusKind]
    ): StateT[F, GlobalSnapshotConsensusState, F[Unit]] =
      StateT[F, GlobalSnapshotConsensusState, F[Unit]] { state =>
        processStateAdvancement(state, resources)
      }

    private def processStateAdvancement(
      state: GlobalSnapshotConsensusState,
      resources: ConsensusResources[GlobalSnapshotArtifact, GlobalConsensusKind]
    ): F[(GlobalSnapshotConsensusState, F[Unit])] =
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

    private def returnStateUnchanged(state: GlobalSnapshotConsensusState): F[(GlobalSnapshotConsensusState, F[Unit])] =
      (state, Applicative[F].unit).pure[F]

    private def attemptStateTransition(
      state: GlobalSnapshotConsensusState,
      resources: ConsensusResources[GlobalSnapshotArtifact, GlobalConsensusKind]
    ): F[Option[StateTransition]] =
      state.status match {
        case s: CollectingFacilities =>
          handleCollectingFacilities(state, s, resources)
        case s: CollectingProposals =>
          handleCollectingProposals(state, s, resources)
        case s: CollectingSignatures =>
          handleCollectingSignatures(state, s, resources)
        case _: Finished =>
          none[StateTransition].pure[F]
      }

    private def handleCollectingFacilities(
      state: GlobalSnapshotConsensusState,
      status: CollectingFacilities,
      resources: ConsensusResources[GlobalSnapshotArtifact, GlobalConsensusKind]
    ): F[Option[StateTransition]] =
      for {
        maybeFacilities <- maybeGetAllDeclarations(state, resources)(_.facility)
        _ <- maybeFacilities.traverse_(facilities => checkForForkingFacilitators(facilities, status.facilitatorsHash))
        result <- maybeFacilities.flatTraverse(facilities => processFacilitiesData(state, facilities))
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
      state: GlobalSnapshotConsensusState,
      facilities: SortedMap[PeerId, Facility]
    ): F[Option[StateTransition]] = {
      val aggregated = facilities.foldMap(f => (f.upperBound, f.candidates.value, f.trigger.toList))
      val (bound, candidates, triggers) = aggregated

      pickMajority(triggers).flatTraverse { majorityTrigger =>
        transitionToProposals(state, bound, candidates, majorityTrigger).map(_.some)
      }
    }

    private def transitionToProposals(
      state: GlobalSnapshotConsensusState,
      bound: Bound,
      candidates: Set[PeerId],
      majorityTrigger: ConsensusTrigger
    ): F[StateTransition] =
      for {
        _ <- clearTimeTriggerIfNeeded(majorityTrigger)
        facilitatorsHash <- computeFacilitatorsHash(state)

        peerEvents <- consensusStorage.pullEvents(bound)
        events = extractEvents(peerEvents)

        proposalData <- createProposalArtifact(state, majorityTrigger, events)
        (artifact, context, returnedEvents) = proposalData

        _ <- storeReturnedEvents(peerEvents, returnedEvents)
        hash <- hashArtifact(artifact)

        _ <- checkForFollowerExit(state)

        sideEffect = spreadProposal(state, hash, facilitatorsHash, artifact)
        newState = buildProposalsState(state, majorityTrigger, artifact, context, hash, candidates, facilitatorsHash)

      } yield StateTransition(newState, sideEffect)

    private def clearTimeTriggerIfNeeded(trigger: ConsensusTrigger): F[Unit] =
      Applicative[F].whenA(trigger === TimeTrigger)(consensusStorage.clearTimeTrigger)

    private def computeFacilitatorsHash(state: GlobalSnapshotConsensusState): F[Hash] =
      HasherSelector[F].withCurrent(implicit hasher => state.facilitators.value.hash)

    private def extractEvents(peerEvents: Map[PeerId, List[(Ordinal, event.GlobalSnapshotEvent)]]): Set[GlobalSnapshotEvent] =
      peerEvents.toList.flatMap(_._2).map(_._2).toSet

    private def createProposalArtifact(
      state: GlobalSnapshotConsensusState,
      majorityTrigger: ConsensusTrigger,
      events: Set[GlobalSnapshotEvent]
    ): F[(GlobalSnapshotArtifact, GlobalSnapshotContext, Set[GlobalSnapshotEvent])] =
      HasherSelector[F].withCurrent { implicit hasher =>
        val lastArtifact = state.lastOutcome.finished.signedMajorityArtifact
        lastArtifact.toHashed.flatMap { hashedLastArtifact =>
          consensusFns.createProposalArtifact(
            state.key,
            hashedLastArtifact.signed,
            state.lastOutcome.finished.context,
            HasherSelector[F].getForOrdinal(lastArtifact.ordinal),
            majorityTrigger,
            events,
            state.facilitators.value.toSet,
            getGlobalSnapshotByOrdinal
          )
        }
      }

    private def storeReturnedEvents(
      peerEvents: Map[PeerId, List[(Ordinal, event.GlobalSnapshotEvent)]],
      returnedEvents: Set[GlobalSnapshotEvent]
    ): F[Unit] = {
      val returnedPeerEvents = peerEvents.map {
        case (peerId, events) =>
          (peerId, events.filter { case (_, event) => returnedEvents.contains(event) })
      }.filter { case (_, events) => events.nonEmpty }

      consensusStorage.addEvents(returnedPeerEvents)
    }

    private def hashArtifact(artifact: GlobalSnapshotArtifact): F[Hash] =
      HasherSelector[F].withCurrent(implicit hasher => artifact.hash)

    private def checkForFollowerExit(state: GlobalSnapshotConsensusState): F[Unit] = {
      val facilitators = state.facilitators.value
      ExitOnFork.exitOnCheck("CL_EXIT_ON_FOLLOWER_ADVANCER", () => facilitators.toSet)
    }

    private def spreadProposal(
      state: GlobalSnapshotConsensusState,
      hash: Hash,
      facilitatorsHash: Hash,
      artifact: GlobalSnapshotArtifact
    ): F[Unit] = {
      val proposal = Proposal(hash, facilitatorsHash)

      for {
        _ <- gossip.spread(ConsensusPeerDeclaration(state.key, proposal))
        _ <- gossip.spreadCommon(ConsensusArtifact(state.key, artifact))
      } yield ()
    }

    private def buildProposalsState(
      state: GlobalSnapshotConsensusState,
      majorityTrigger: ConsensusTrigger,
      artifact: GlobalSnapshotArtifact,
      context: GlobalSnapshotContext,
      hash: Hash,
      candidates: Set[PeerId],
      facilitatorsHash: Hash
    ): GlobalSnapshotConsensusState =
      state.copy(
        status = CollectingProposals(
          majorityTrigger,
          ArtifactInfo(artifact, context, hash),
          Candidates(candidates),
          facilitatorsHash
        )
      )

    private def handleCollectingProposals(
      state: GlobalSnapshotConsensusState,
      status: CollectingProposals,
      resources: ConsensusResources[GlobalSnapshotArtifact, GlobalConsensusKind]
    ): F[Option[StateTransition]] =
      HasherSelector[F].withCurrent { implicit hasher =>
        for {
          maybeAllProposals <- maybeGetAllDeclarations(state, resources)(_.proposal)
          _ <- maybeAllProposals.traverse_(proposals => checkForForkingProposals(proposals, status.facilitatorsHash))
          result <- maybeAllProposals.flatTraverse(proposals => processProposalsData(state, status, resources, proposals))
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
      state: GlobalSnapshotConsensusState,
      status: CollectingProposals,
      resources: ConsensusResources[GlobalSnapshotArtifact, GlobalConsensusKind],
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
      state: GlobalSnapshotConsensusState,
      status: CollectingProposals,
      resources: ConsensusResources[GlobalSnapshotArtifact, GlobalConsensusKind],
      allProposalHashes: List[Hash]
    )(implicit hasher: Hasher[F]): F[Option[ArtifactInfo[GlobalSnapshotArtifact, GlobalSnapshotContext]]] = {
      val lastArtifact = state.lastOutcome.finished.signedMajorityArtifact

      lastArtifact.toHashed.flatMap { hashedLastArtifact =>
        pickValidatedMajorityArtifact(
          status.proposalArtifactInfo,
          hashedLastArtifact.signed,
          state.lastOutcome.finished.context,
          status.majorityTrigger,
          resources,
          allProposalHashes,
          state.facilitators.value.toSet,
          consensusFns,
          getGlobalSnapshotByOrdinal
        )
      }
    }

    private def transitionToSignatures(
      state: GlobalSnapshotConsensusState,
      status: CollectingProposals,
      majorityArtifactInfo: ArtifactInfo[GlobalSnapshotArtifact, GlobalSnapshotContext],
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
      state: GlobalSnapshotConsensusState,
      signature: Signature,
      facilitatorsHash: Hash
    ): F[Unit] = {
      val majoritySignature = MajoritySignature(signature, facilitatorsHash)

      for {
        _ <- gossip.spread(ConsensusPeerDeclaration(state.key, majoritySignature))
      } yield ()
    }

    private def buildSignaturesState(
      state: GlobalSnapshotConsensusState,
      status: CollectingProposals,
      majorityArtifactInfo: ArtifactInfo[GlobalSnapshotArtifact, GlobalSnapshotContext],
      facilitatorsHash: Hash
    ): GlobalSnapshotConsensusState =
      state.copy(
        status = CollectingSignatures(
          majorityArtifactInfo,
          status.majorityTrigger,
          status.candidates,
          facilitatorsHash
        )
      )

    private def handleCollectingSignatures(
      state: GlobalSnapshotConsensusState,
      status: CollectingSignatures,
      resources: ConsensusResources[GlobalSnapshotArtifact, GlobalConsensusKind]
    ): F[Option[StateTransition]] =
      for {
        maybeAllSignatures <- maybeGetAllDeclarations(state, resources)(_.signature)
        _ <- maybeAllSignatures.traverse_(signatures => checkForForkingSignatures(signatures, status.facilitatorsHash))
        result <- maybeAllSignatures.flatTraverse(signatures => processSignaturesData(state, status, signatures))
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

    private def processSignaturesData(
      state: GlobalSnapshotConsensusState,
      status: CollectingSignatures,
      signatures: SortedMap[PeerId, MajoritySignature]
    ): F[Option[StateTransition]] = {
      val allSignatureProofs = signatures.map {
        case (id, sig) =>
          SignatureProof(PeerId._Id.get(id), sig.signature)
      }.toList

      validateAndFinalize(state, status, allSignatureProofs)
    }

    private def validateAndFinalize(
      state: GlobalSnapshotConsensusState,
      status: CollectingSignatures,
      allSignatures: List[SignatureProof]
    ): F[Option[StateTransition]] =
      allSignatures
        .filterA(sig => verifySignatureProof(status.majorityArtifactInfo.hash, sig))
        .flatTap(validSignatures => logInvalidSignaturesIfAny(state, allSignatures.size, validSignatures.size))
        .flatMap(validSignatures => transitionToFinished(state, status, validSignatures))

    private def logInvalidSignaturesIfAny(
      state: GlobalSnapshotConsensusState,
      totalCount: Int,
      validCount: Int
    ): F[Unit] =
      logger
        .warn(
          s"Removed ${(totalCount - validCount).show} invalid signatures during consensus for key ${state.key.show}, " +
            s"${validCount.show} valid signatures left"
        )
        .whenA(totalCount =!= validCount)

    private def transitionToFinished(
      state: GlobalSnapshotConsensusState,
      status: CollectingSignatures,
      validSignatures: List[SignatureProof]
    ): F[Option[StateTransition]] =
      HasherSelector[F].withCurrent { implicit hasher =>
        state.facilitators.value.hash
      }.map { facilitatorsHash =>
        NonEmptySet.fromSet(validSignatures.toSortedSet).map { validSignaturesNes =>
          buildFinishedTransition(state, status, validSignaturesNes, facilitatorsHash)
        }
      }

    private def buildFinishedTransition(
      state: GlobalSnapshotConsensusState,
      status: CollectingSignatures,
      signatures: NonEmptySet[SignatureProof],
      facilitatorsHash: Hash
    ): StateTransition = {
      val signedArtifact = Signed(status.majorityArtifactInfo.artifact, signatures)

      val finishedStatus: GlobalConsensusStep = Finished(
        signedArtifact,
        status.majorityArtifactInfo.context,
        status.majorityTrigger,
        status.candidates,
        facilitatorsHash
      )

      val newState = state.copy(status = finishedStatus)

      val sideEffect = persistSnapshotAndGossip(signedArtifact, status.majorityArtifactInfo.context)

      StateTransition(newState, sideEffect)
    }

    private def persistSnapshotAndGossip(
      signedArtifact: Signed[GlobalIncrementalSnapshot],
      context: GlobalSnapshotContext
    ): F[Unit] = {
      val persistEffect = HasherSelector[F].withCurrent { implicit hasher =>
        for {
          hashedSnapshot <- signedArtifact.toHashed
          _ <- lastNGlobalSnapshotStorage.set(hashedSnapshot, context)
          _ <- lastGlobalSnapshotStorage.set(hashedSnapshot, context)
          result <- globalSnapshotStorage.prepend(signedArtifact, context)
        } yield result
      }

      val gossipEffect = HasherSelector[F].withCurrent { implicit hasher =>
        gossipForkInfo(gossip, signedArtifact)
      }

      persistEffect.ifM(
        metrics.globalSnapshot(signedArtifact) >> gossipEffect,
        logger.error("Cannot save GlobalSnapshot into the storage") *>
          MonadThrow[F].raiseError[Unit](new RuntimeException("Failed to persist GlobalSnapshot"))
      )
    }

    object metrics {
      def globalSnapshot(signedGS: Signed[GlobalIncrementalSnapshot]): F[Unit] = {
        val activeTipsCount = signedGS.tips.remainedActive.size + signedGS.blocks.size
        val deprecatedTipsCount = signedGS.tips.deprecated.size
        val transactionCount = signedGS.blocks.toList.map(_.block.transactions.size).sum
        val scSnapshotCount = signedGS.stateChannelSnapshots.view.values.map(_.size).sum

        Metrics[F].updateGauge("dag_global_snapshot_ordinal", signedGS.ordinal.value) >>
          Metrics[F].updateGauge("dag_global_snapshot_height", signedGS.height.value) >>
          Metrics[F].updateGauge("dag_global_snapshot_signature_count", signedGS.proofs.size) >>
          Metrics[F].updateGauge("dag_global_snapshot_tips_count", deprecatedTipsCount, Seq(("tip_type", "deprecated"))) >>
          Metrics[F].updateGauge("dag_global_snapshot_tips_count", activeTipsCount, Seq(("tip_type", "active"))) >>
          Metrics[F].incrementCounterBy("dag_global_snapshot_blocks_total", signedGS.blocks.size) >>
          Metrics[F].incrementCounterBy("dag_global_snapshot_transactions_total", transactionCount) >>
          Metrics[F].incrementCounterBy("dag_global_snapshot_state_channel_snapshots_total", scSnapshotCount)
      }
    }
  }
}
