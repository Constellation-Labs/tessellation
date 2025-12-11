package io.constellationnetwork.dag.l0.infrastructure.snapshot

import java.security.KeyPair

import cats.data.{NonEmptySet, StateT}
import cats.effect.Async
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

/** Advances Global L0 consensus through status phases and extracts final outcomes.
  *
  * Status Flow:
  * {{{
  *   CollectingFacilities → CollectingProposals → CollectingSignatures → Finished
  * }}}
  *
  * @see
  *   ConsensusStateAdvancer for the generic interface
  */
abstract class GlobalSnapshotConsensusStateAdvancer[F[_]]
    extends ConsensusStateAdvancer[
      F,
      GlobalSnapshotKey,
      GlobalSnapshotArtifact,
      GlobalSnapshotContext,
      GlobalSnapshotStatus,
      GlobalConsensusOutcome,
      GlobalConsensusKind
    ]

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

    private val logger: SelfAwareStructuredLogger[F] = Slf4jLogger.getLoggerFromClass[F](getClass)
    private val lastSnapshotHashObservationName = "last-snapshot-hash"

    protected val clusterStorage: ClusterStorage[F] = clusterStorageInstance
    protected val config: ConsensusConfig = consensusConfig

    private case class Transition(newState: GlobalSnapshotConsensusState, sideEffect: F[Unit])

    def getConsensusOutcome(
      state: GlobalSnapshotConsensusState
    ): Option[(Previous[GlobalSnapshotKey], GlobalConsensusOutcome)] =
      state.status match {
        case f: Finished =>
          val outcome = GlobalConsensusOutcome(
            state.key,
            state.facilitators,
            state.removedFacilitators,
            state.withdrawnFacilitators,
            Finished(f.signedMajorityArtifact, f.context, f.majorityTrigger, f.candidates, f.facilitatorsHash, f.snapshotHash)
          )
          (Previous(state.lastOutcome.key), outcome).some
        case _ =>
          none
      }

    def advanceStatus(
      resources: ConsensusResources[GlobalSnapshotArtifact, GlobalConsensusKind]
    ): StateT[F, GlobalSnapshotConsensusState, F[Unit]] =
      StateT { state =>
        if (state.lockStatus === LockStatus.Closed)
          (state, Applicative[F].unit).pure[F]
        else
          tryAdvance(state, resources).map {
            case Some(t) => (t.newState.copy(lockStatus = LockStatus.Open), t.sideEffect)
            case None    => (state, Applicative[F].unit)
          }
      }

    private def tryAdvance(
      state: GlobalSnapshotConsensusState,
      resources: ConsensusResources[GlobalSnapshotArtifact, GlobalConsensusKind]
    ): F[Option[Transition]] =
      state.status match {
        case s: CollectingFacilities => advanceFromFacilities(state, s, resources)
        case s: CollectingProposals  => advanceFromProposals(state, s, resources)
        case s: CollectingSignatures => advanceFromSignatures(state, s, resources)
        case _: Finished             => none[Transition].pure[F]
      }

    // =========================================================================
    // COLLECTING FACILITIES → COLLECTING PROPOSALS
    // =========================================================================

    private def advanceFromFacilities(
      state: GlobalSnapshotConsensusState,
      status: CollectingFacilities,
      resources: ConsensusResources[GlobalSnapshotArtifact, GlobalConsensusKind]
    ): F[Option[Transition]] =
      for {
        maybeFacilities <- maybeGetAllDeclarations(state, resources)(_.facility)
        _ <- maybeFacilities.traverse_(checkForkByLastSnapshotHash(_, status.lastSnapshotHash))
        result <- maybeFacilities.flatTraverse(toProposalsPhase(state, _))
      } yield result

    private def toProposalsPhase(
      state: GlobalSnapshotConsensusState,
      facilities: SortedMap[PeerId, Facility]
    ): F[Option[Transition]] = {
      val (bound, candidates, triggers) = facilities.foldMap(f => (f.upperBound, f.candidates.value, f.trigger.toList))

      pickMajority(triggers).flatTraverse { majorityTrigger =>
        buildProposalTransition(state, bound, candidates, majorityTrigger).map(_.some)
      }
    }

    private def buildProposalTransition(
      state: GlobalSnapshotConsensusState,
      bound: Bound,
      candidates: Set[PeerId],
      majorityTrigger: ConsensusTrigger
    ): F[Transition] =
      for {
        _ <- clearTimeTriggerIfNeeded(majorityTrigger)
        facilitatorsHash <- hashFacilitators(state)
        peerEvents <- consensusStorage.pullEvents(bound)

        (artifact, context, returnedEvents) <- createArtifact(state, majorityTrigger, extractEvents(peerEvents))

        _ <- storeReturnedEvents(peerEvents, returnedEvents)
        hash <- hashArtifact(artifact)
        _ <- checkFollowerExit(state)
      } yield
        Transition(
          newState = state.copy(status =
            CollectingProposals(
              majorityTrigger,
              ArtifactInfo(artifact, context, hash),
              Candidates(candidates),
              facilitatorsHash,
              state.lastOutcome.finished.snapshotHash
            )
          ),
          sideEffect = spreadProposal(state.key, hash, facilitatorsHash, artifact, state.lastOutcome.finished.snapshotHash)
        )

    // =========================================================================
    // COLLECTING PROPOSALS → COLLECTING SIGNATURES
    // =========================================================================

    private def advanceFromProposals(
      state: GlobalSnapshotConsensusState,
      status: CollectingProposals,
      resources: ConsensusResources[GlobalSnapshotArtifact, GlobalConsensusKind]
    ): F[Option[Transition]] =
      HasherSelector[F].withCurrent { implicit hasher =>
        for {
          maybeProposals <- maybeGetAllDeclarations(state, resources)(_.proposal)
          _ <- maybeProposals.traverse_(checkForkByLastSnapshotHash(_, status.lastSnapshotHash))
          result <- maybeProposals.flatTraverse(toSignaturesPhase(state, status, resources, _))
        } yield result
      }

    private def toSignaturesPhase(
      state: GlobalSnapshotConsensusState,
      status: CollectingProposals,
      resources: ConsensusResources[GlobalSnapshotArtifact, GlobalConsensusKind],
      proposals: SortedMap[PeerId, Proposal]
    )(implicit hasher: Hasher[F]): F[Option[Transition]] = {
      val hashes = proposals.values.toList.map(_.hash)

      findMajorityArtifact(state, status, resources, hashes).flatMap {
        case Some(majorityInfo) => buildSignatureTransition(state, status, majorityInfo, hashes).map(_.some)
        case None               => none[Transition].pure[F]
      }
    }

    private def findMajorityArtifact(
      state: GlobalSnapshotConsensusState,
      status: CollectingProposals,
      resources: ConsensusResources[GlobalSnapshotArtifact, GlobalConsensusKind],
      proposalHashes: List[Hash]
    )(implicit hasher: Hasher[F]): F[Option[ArtifactInfo[GlobalSnapshotArtifact, GlobalSnapshotContext]]] =
      state.lastOutcome.finished.signedMajorityArtifact.toHashed.flatMap { hashedLast =>
        pickValidatedMajorityArtifact(
          status.proposalArtifactInfo,
          hashedLast.signed,
          state.lastOutcome.finished.context,
          status.majorityTrigger,
          resources,
          proposalHashes,
          state.facilitators.value.toSet,
          consensusFns,
          getGlobalSnapshotByOrdinal
        )
      }

    private def buildSignatureTransition(
      state: GlobalSnapshotConsensusState,
      status: CollectingProposals,
      majorityInfo: ArtifactInfo[GlobalSnapshotArtifact, GlobalSnapshotContext],
      proposalHashes: List[Hash]
    )(implicit hasher: Hasher[F]): F[Transition] =
      for {
        facilitatorsHash <- state.facilitators.value.hash
        signature <- Signature.fromHash(keyPair.getPrivate, majorityInfo.hash)
        _ <- recordProposalAffinity(proposalHashes, status.proposalArtifactInfo.hash)
      } yield
        Transition(
          newState = state.copy(status =
            CollectingSignatures(
              majorityInfo,
              status.majorityTrigger,
              status.candidates,
              facilitatorsHash,
              state.lastOutcome.finished.snapshotHash
            )
          ),
          sideEffect = spreadSignature(state.key, signature, facilitatorsHash, state.lastOutcome.finished.snapshotHash)
        )

    // =========================================================================
    // COLLECTING SIGNATURES → FINISHED
    // =========================================================================

    private def advanceFromSignatures(
      state: GlobalSnapshotConsensusState,
      status: CollectingSignatures,
      resources: ConsensusResources[GlobalSnapshotArtifact, GlobalConsensusKind]
    ): F[Option[Transition]] =
      for {
        maybeSignatures <- maybeGetAllDeclarations(state, resources)(_.signature)
        _ <- maybeSignatures.traverse_(checkForkByLastSnapshotHash(_, status.lastSnapshotHash))
        result <- maybeSignatures.flatTraverse(toFinishedPhase(state, status, _))
      } yield result

    private def toFinishedPhase(
      state: GlobalSnapshotConsensusState,
      status: CollectingSignatures,
      signatures: SortedMap[PeerId, MajoritySignature]
    ): F[Option[Transition]] = {
      val proofs = signatures.map { case (id, sig) => SignatureProof(PeerId._Id.get(id), sig.signature) }.toList

      for {
        valid <- proofs.filterA(verifySignatureProof(status.majorityArtifactInfo.hash, _))
        _ <- logInvalidSignatures(state.key, proofs.size, valid.size)
        result <- buildFinishedTransition(state, status, valid)
      } yield result
    }

    private def buildFinishedTransition(
      state: GlobalSnapshotConsensusState,
      status: CollectingSignatures,
      validSignatures: List[SignatureProof]
    ): F[Option[Transition]] =
      for {
        facilitatorsHash <- HasherSelector[F].withCurrent(implicit h => state.facilitators.value.hash)
        result <- NonEmptySet.fromSet(validSignatures.toSortedSet).traverse { signaturesNes =>
          val signedArtifact = Signed(status.majorityArtifactInfo.artifact, signaturesNes)
          for {
            snapshotHash <- HasherSelector[F].withCurrent(implicit h => signedArtifact.hash)
            result = Transition(
              newState = state.copy(status =
                Finished(
                  signedArtifact,
                  status.majorityArtifactInfo.context,
                  status.majorityTrigger,
                  status.candidates,
                  facilitatorsHash,
                  snapshotHash
                )
              ),
              sideEffect = persistAndGossip(signedArtifact, status.majorityArtifactInfo.context)
            )
          } yield result
        }
      } yield result

    private def hashFacilitators(state: GlobalSnapshotConsensusState): F[Hash] =
      HasherSelector[F].withCurrent(implicit h => state.facilitators.value.hash)

    private def hashArtifact(artifact: GlobalSnapshotArtifact): F[Hash] =
      HasherSelector[F].withCurrent(implicit h => artifact.hash)

    private def extractEvents(peerEvents: Map[PeerId, List[(Ordinal, GlobalSnapshotEvent)]]): Set[GlobalSnapshotEvent] =
      peerEvents.values.flatten.map(_._2).toSet

    private def createArtifact(
      state: GlobalSnapshotConsensusState,
      trigger: ConsensusTrigger,
      events: Set[GlobalSnapshotEvent]
    ): F[(GlobalSnapshotArtifact, GlobalSnapshotContext, Set[GlobalSnapshotEvent])] =
      HasherSelector[F].withCurrent { implicit hasher =>
        val lastArtifact = state.lastOutcome.finished.signedMajorityArtifact
        lastArtifact.toHashed.flatMap { hashed =>
          consensusFns.createProposalArtifact(
            state.key,
            hashed.signed,
            state.lastOutcome.finished.context,
            HasherSelector[F].getForOrdinal(lastArtifact.ordinal),
            trigger,
            events,
            state.facilitators.value.toSet,
            getGlobalSnapshotByOrdinal
          )
        }
      }

    private def storeReturnedEvents(
      peerEvents: Map[PeerId, List[(Ordinal, GlobalSnapshotEvent)]],
      returnedEvents: Set[GlobalSnapshotEvent]
    ): F[Unit] = {
      val filtered = peerEvents.map { case (pid, evts) => (pid, evts.filter { case (_, e) => returnedEvents.contains(e) }) }
        .filter(_._2.nonEmpty)
      consensusStorage.addEvents(filtered)
    }

    private def spreadProposal(
      key: GlobalSnapshotKey,
      hash: Hash,
      facilitatorsHash: Hash,
      artifact: GlobalSnapshotArtifact,
      lastSnapshotHash: Hash
    ): F[Unit] =
      gossip.spread(ConsensusPeerDeclaration(key, Proposal(hash, facilitatorsHash, lastSnapshotHash))) >>
        gossip.spreadCommon(ConsensusArtifact(key, artifact))

    private def spreadSignature(key: GlobalSnapshotKey, signature: Signature, facilitatorsHash: Hash, lastSnapshotHash: Hash): F[Unit] =
      gossip.spread(ConsensusPeerDeclaration(key, MajoritySignature(signature, facilitatorsHash, lastSnapshotHash)))

    private def persistAndGossip(signedArtifact: Signed[GlobalIncrementalSnapshot], context: GlobalSnapshotContext): F[Unit] = {
      val persist = HasherSelector[F].withCurrent { implicit h =>
        for {
          hashed <- signedArtifact.toHashed
          _ <- lastNGlobalSnapshotStorage.set(hashed, context)
          _ <- lastGlobalSnapshotStorage.set(hashed, context)
          ok <- globalSnapshotStorage.prepend(signedArtifact, context)
        } yield ok
      }

      val gossipFork = HasherSelector[F].withCurrent(implicit h => gossipForkInfo(gossip, signedArtifact))

      persist.ifM(
        recordMetrics(signedArtifact) >> gossipFork,
        logger.error("Cannot save GlobalSnapshot") >> MonadThrow[F].raiseError(new RuntimeException("Persist failed"))
      )
    }

    private def checkForkByLastSnapshotHash[A](declarations: SortedMap[PeerId, A], ownHash: Hash)(
      implicit extract: A => Hash
    ): F[Unit] =
      recoverIfForking[F](ownHash, lastSnapshotHashObservationName, restartService, nodeStorage, leavingDelay)(
        declarations.map { case (pid, decl) => (pid, extract(decl)) }
      )

    private implicit val extractFacilityHash: Facility => Hash = _.lastSnapshotHash
    private implicit val extractProposalHash: Proposal => Hash = _.lastSnapshotHash
    private implicit val extractSignatureHash: MajoritySignature => Hash = _.lastSnapshotHash

    private def checkFollowerExit(state: GlobalSnapshotConsensusState): F[Unit] =
      ExitOnFork.exitOnCheck("CL_EXIT_ON_FOLLOWER_ADVANCER", () => state.facilitators.value.toSet)

    private def clearTimeTriggerIfNeeded(trigger: ConsensusTrigger): F[Unit] =
      Applicative[F].whenA(trigger === TimeTrigger)(consensusStorage.clearTimeTrigger)

    private def recordProposalAffinity(allHashes: List[Hash], ownHash: Hash): F[Unit] =
      Metrics[F].recordDistribution("dag_consensus_proposal_affinity", proposalAffinity(allHashes, ownHash))

    private def logInvalidSignatures(key: GlobalSnapshotKey, total: Int, valid: Int): F[Unit] =
      logger
        .warn(s"Removed ${total - valid} invalid signatures for key=${key.show}, $valid valid remaining")
        .whenA(total != valid)

    private def recordMetrics(signed: Signed[GlobalIncrementalSnapshot]): F[Unit] = {
      val activeTips = signed.tips.remainedActive.size + signed.blocks.size
      val deprecatedTips = signed.tips.deprecated.size
      val txCount = signed.blocks.toList.map(_.block.transactions.size).sum
      val scCount = signed.stateChannelSnapshots.values.map(_.size).sum

      Metrics[F].updateGauge("dag_global_snapshot_ordinal", signed.ordinal.value) >>
        Metrics[F].updateGauge("dag_global_snapshot_height", signed.height.value) >>
        Metrics[F].updateGauge("dag_global_snapshot_signature_count", signed.proofs.size) >>
        Metrics[F].updateGauge("dag_global_snapshot_tips_count", deprecatedTips, Seq(("tip_type", "deprecated"))) >>
        Metrics[F].updateGauge("dag_global_snapshot_tips_count", activeTips, Seq(("tip_type", "active"))) >>
        Metrics[F].incrementCounterBy("dag_global_snapshot_blocks_total", signed.blocks.size) >>
        Metrics[F].incrementCounterBy("dag_global_snapshot_transactions_total", txCount) >>
        Metrics[F].incrementCounterBy("dag_global_snapshot_state_channel_snapshots_total", scCount)
    }
  }
}
