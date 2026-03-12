package io.constellationnetwork.dag.l0.infrastructure.snapshot

import java.security.KeyPair
import java.time.Instant

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
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.{ConsensusTrigger, EventTrigger, TimeTrigger}
import io.constellationnetwork.node.shared.infrastructure.fork.ExitOnFork
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.node.shared.infrastructure.node.RestartService
import io.constellationnetwork.node.shared.infrastructure.snapshot.SnapshotConsensusFunctions.gossipForkInfo
import io.constellationnetwork.node.shared.logger.LoggerBundle
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
    clusterStorageInstance: ClusterStorage[F],
    loggerBundle: LoggerBundle[F]
  ): GlobalSnapshotConsensusStateAdvancer[F] = new GlobalSnapshotConsensusStateAdvancer[F] {

    private val logger: SelfAwareStructuredLogger[F] = Slf4jLogger.getLoggerFromClass[F](getClass)
    private val lastSnapshotHashObservationName = "last-snapshot-hash"
    private val facilitatorsHashObservationName = "facilitators-hash"
    private val consensusConfigHashObservationName = "consensus-config-hash"

    protected val clusterStorage: ClusterStorage[F] = clusterStorageInstance
    protected val config: ConsensusConfig = consensusConfig

    private case class Transition(newState: GlobalSnapshotConsensusState, sideEffect: F[Unit])

    def getConsensusOutcome(
      state: GlobalSnapshotConsensusState
    ): Option[(Previous[GlobalSnapshotKey], GlobalConsensusOutcome)] =
      state.status match {
        case f: Finished =>
          // Compute removal penalties: decrement previous, add new removals
          val previousPenalties = state.lastOutcome.removalPenalties
          val decrementedPenalties = previousPenalties.view.mapValues(_ - 1).filter(_._2 > 0).toMap
          val newPenalties = state.removedFacilitators.value.foldLeft(decrementedPenalties) { (acc, pid) =>
            acc.updated(pid, config.removalPenaltyRounds)
          }
          val outcome = GlobalConsensusOutcome(
            state.key,
            state.facilitators,
            state.removedFacilitators,
            state.withdrawnFacilitators,
            state.eligibleFacilitators,
            Finished(f.signedMajorityArtifact, f.context, f.majorityTrigger, f.candidates, f.facilitatorsHash, f.snapshotHash),
            removalPenalties = if (config.removalPenaltyRounds > 0) newPenalties else Map.empty
          )
          (Previous(state.lastOutcome.key), outcome).some
        case _ =>
          none
      }

    def advanceStatus(
      resources: ConsensusResources[GlobalSnapshotArtifact, GlobalConsensusKind]
    ): StateT[F, GlobalSnapshotConsensusState, F[Unit]] =
      StateT { state =>
        tryAdvance(state, resources).map {
          case Some(t) => (t.newState, t.sideEffect)
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
      loggerBundle.app.withOrdinal(SnapshotOrdinal.unsafeApply(state.lastOutcome.key.value.value + 1)) {
        HasherSelector[F].withCurrent { implicit hasher =>
          for {
            maybeFacilities <- maybeGetQuorumDeclarations(state, resources)(_.facility)(_.lastSnapshotHash)
            facilitators = maybeFacilities.map(_.keys.toList).getOrElse(List.empty[PeerId])
            _ <- loggerBundle.consensus.collectingFacilities(facilitators)
            _ <- maybeFacilities.traverse_(checkForkByFacilitatorsHash(_, status.facilitatorsHash)(_.facilitatorsHash))
            _ <- maybeFacilities.traverse_(checkForkByLastSnapshotHash(_, status.lastSnapshotHash))
            _ <- maybeFacilities.traverse_(checkForkByConsensusConfigHash)
            result <- maybeFacilities.flatTraverse(toProposalsPhase(state, _))
          } yield result
        }
      }

    private def toProposalsPhase(
      state: GlobalSnapshotConsensusState,
      facilities: SortedMap[PeerId, Facility]
    ): F[Option[Transition]] = {
      val (bound, candidates, triggers) = facilities.foldMap(f => (f.upperBound, f.candidates.value, f.trigger.toList))

      val trigger = pickMajority(triggers).getOrElse(EventTrigger)
      buildProposalTransition(state, bound, candidates, trigger).map(_.some)
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
        isLeader = selfId === state.leader
        role = if (isLeader) "LEADER" else "FOLLOWER"
        withdrawnCount = state.withdrawnFacilitators.value.size
        _ <- logger.info(
          s"[CONSENSUS:$role] FACILITIES→PROPOSALS\n" +
            s"  key=${state.key.show} ordinal=${artifact.ordinal.show} trigger=$majorityTrigger\n" +
            s"  hash=${hash.show.take(8)}... facilitators=${state.facilitators.value.size} candidates=${candidates.size}\n" +
            s"  leader=${state.leader.show.take(8)}... self=${selfId.show.take(8)}... view=${state.viewNumber}" +
            (if (withdrawnCount > 0) s" withdrawn=$withdrawnCount" else "") +
            s"\n  facilitatorsHash=${facilitatorsHash.show.take(8)}... lastSnapshotHash=${state.lastOutcome.finished.snapshotHash.show
                .take(8)}... entropy=${state.entropy.show.take(8)}..."
        )
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
          sideEffect =
            if (isLeader)
              spreadProposal(state, state.key, hash, facilitatorsHash, artifact, state.lastOutcome.finished.snapshotHash)
            else
              Applicative[F].unit
        )

    // =========================================================================
    // COLLECTING PROPOSALS → COLLECTING SIGNATURES
    // =========================================================================

    // ---- Leader-based proposal resolution ----
    // Only the leader spreads a Proposal + ConsensusArtifact. Non-leaders wait for the leader's
    // proposal to arrive via gossip, then validate the leader's artifact to obtain the context
    // needed for signing. If the leader's hash matches our own artifact, we use our local
    // ArtifactInfo directly (no extra validation needed).

    private def advanceFromProposals(
      state: GlobalSnapshotConsensusState,
      status: CollectingProposals,
      resources: ConsensusResources[GlobalSnapshotArtifact, GlobalConsensusKind]
    ): F[Option[Transition]] =
      loggerBundle.app.withOrdinal(status.proposalArtifactInfo.artifact.ordinal) {
        HasherSelector[F].withCurrent { implicit hasher =>
          val leader = state.leader
          val maybeLeaderProposal = resources.peerDeclarationsMap.get(leader).flatMap(_.proposal)

          maybeLeaderProposal match {
            case Some(leaderProposal) =>
              for {
                _ <- loggerBundle.consensus.collectingProposals(List(leader))
                _ <- checkForkByFacilitatorsHash(
                  SortedMap(leader -> leaderProposal),
                  status.facilitatorsHash
                )(_.facilitatorsHash)
                _ <- checkForkByLastSnapshotHash(
                  SortedMap(leader -> leaderProposal),
                  status.lastSnapshotHash
                )
                result <- resolveLeaderProposal(state, status, resources, leaderProposal)
              } yield result
            case None =>
              if (selfId === state.leader)
                // Leader (possibly after view change) — spread proposal so peers can advance
                logger.info(
                  s"[CONSENSUS:LEADER] Re-spreading proposal\n" +
                    s"  key=${state.key.show} hash=${status.proposalArtifactInfo.hash.show.take(8)}... " +
                    s"targets=${state.facilitators.value.size} view=${state.viewNumber}"
                ) >>
                  spreadProposal(
                    state,
                    state.key,
                    status.proposalArtifactInfo.hash,
                    status.facilitatorsHash,
                    status.proposalArtifactInfo.artifact,
                    status.lastSnapshotHash
                  ).as(none[Transition])
              else
                none[Transition].pure[F]
          }
        }
      }

    private def resolveLeaderProposal(
      state: GlobalSnapshotConsensusState,
      status: CollectingProposals,
      resources: ConsensusResources[GlobalSnapshotArtifact, GlobalConsensusKind],
      leaderProposal: Proposal
    )(implicit hasher: Hasher[F]): F[Option[Transition]] = {
      val role = if (selfId === state.leader) "LEADER" else "FOLLOWER"
      if (leaderProposal.hash === status.proposalArtifactInfo.hash) {
        // Leader's artifact matches our own — use local ArtifactInfo (avoids re-validation)
        logger.info(
          s"[CONSENSUS:$role] PROPOSALS→SIGNATURES\n" +
            s"  key=${state.key.show} matchesOwn=true hash=${leaderProposal.hash.show.take(8)}...\n" +
            s"  trigger=${status.majorityTrigger} leader=${state.leader.show.take(8)}... self=${selfId.show.take(8)}... view=${state.viewNumber}"
        ) >>
          buildSignatureTransition(state, status, status.proposalArtifactInfo, List(leaderProposal.hash)).map(_.some)
      } else {
        // Leader proposed a different artifact — validate theirs
        resources.artifacts.get(leaderProposal.hash) match {
          case Some(leaderArtifact) =>
            validateLeaderArtifact(state, status, leaderArtifact, leaderProposal.hash).flatMap {
              case Some(leaderInfo) =>
                logger.info(
                  s"[CONSENSUS:$role] PROPOSALS→SIGNATURES\n" +
                    s"  key=${state.key.show} matchesOwn=false\n" +
                    s"  leaderHash=${leaderProposal.hash.show.take(8)}... ownHash=${status.proposalArtifactInfo.hash.show.take(8)}...\n" +
                    s"  trigger=${status.majorityTrigger} leader=${state.leader.show.take(8)}... self=${selfId.show.take(8)}... view=${state.viewNumber}"
                ) >>
                  buildSignatureTransition(state, status, leaderInfo, List(leaderProposal.hash)).map(_.some)
              case None =>
                logger.warn(
                  s"[CONSENSUS:$role] Leader proposal FAILED validation\n" +
                    s"  key=${state.key.show} leaderHash=${leaderProposal.hash.show
                        .take(8)}... ownHash=${status.proposalArtifactInfo.hash.show.take(8)}...\n" +
                    s"  leader=${state.leader.show.take(8)}... view=${state.viewNumber}"
                ) >>
                  none[Transition].pure[F]
            }
          case None =>
            // Leader's artifact not yet received via gossip — wait
            none[Transition].pure[F]
        }
      }
    }

    private def validateLeaderArtifact(
      state: GlobalSnapshotConsensusState,
      status: CollectingProposals,
      artifact: GlobalSnapshotArtifact,
      hash: Hash
    )(implicit hasher: Hasher[F]): F[Option[ArtifactInfo[GlobalSnapshotArtifact, GlobalSnapshotContext]]] =
      state.lastOutcome.finished.signedMajorityArtifact.toHashed.flatMap { hashedLast =>
        consensusFns
          .validateArtifact(
            hashedLast.signed,
            state.lastOutcome.finished.context,
            status.majorityTrigger,
            artifact,
            state.facilitators.value.toSet,
            getGlobalSnapshotByOrdinal
          )
          .map {
            case Right((validatedArtifact, context)) =>
              ArtifactInfo(validatedArtifact, context, hash).some
            case Left(_) =>
              none
          }
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
          sideEffect = spreadSignature(state, state.key, signature, facilitatorsHash, state.lastOutcome.finished.snapshotHash)
        )

    // =========================================================================
    // COLLECTING SIGNATURES → FINISHED
    // =========================================================================

    private def advanceFromSignatures(
      state: GlobalSnapshotConsensusState,
      status: CollectingSignatures,
      resources: ConsensusResources[GlobalSnapshotArtifact, GlobalConsensusKind]
    ): F[Option[Transition]] =
      loggerBundle.app.withOrdinal(status.majorityArtifactInfo.artifact.ordinal) {
        HasherSelector[F].withCurrent { implicit hasher =>
          for {
            maybeSignatures <- maybeGetQuorumDeclarations(state, resources)(_.signature)(_.facilitatorsHash)
            facilitators = maybeSignatures.map(_.keys.toList).getOrElse(List.empty[PeerId])
            _ <- loggerBundle.consensus.collectingSignatures(facilitators)
            _ <- maybeSignatures.traverse_(checkForkByFacilitatorsHash(_, status.facilitatorsHash)(_.facilitatorsHash))
            _ <- maybeSignatures.traverse_(checkForkByLastSnapshotHash(_, status.lastSnapshotHash))
            result <- maybeSignatures.flatTraverse(toFinishedPhase(state, status, _))
          } yield result
        }
      }

    private def toFinishedPhase(
      state: GlobalSnapshotConsensusState,
      status: CollectingSignatures,
      signatures: SortedMap[PeerId, MajoritySignature]
    ): F[Option[Transition]] = {
      val proofs = signatures.map { case (id, sig) => SignatureProof(PeerId._Id.get(id), sig.signature) }.toList

      for {
        valid <- proofs.filterA(verifySignatureProof(status.majorityArtifactInfo.hash, _))
        _ <- logInvalidSignatures(state.key, proofs.size, valid.size)
        role = if (selfId === state.leader) "LEADER" else "FOLLOWER"
        _ <- logger.info(
          s"[CONSENSUS:$role] SIGNATURES→FINISHED\n" +
            s"  key=${state.key.show} ordinal=${status.majorityArtifactInfo.artifact.ordinal.show}\n" +
            s"  signatures=${valid.size}/${proofs.size} hash=${status.majorityArtifactInfo.hash.show.take(8)}...\n" +
            s"  trigger=${status.majorityTrigger} leader=${state.leader.show.take(8)}... self=${selfId.show.take(8)}... view=${state.viewNumber}"
        )
        result <- buildFinishedTransition(state, status, valid)
      } yield result
    }

    private def buildFinishedTransition(
      state: GlobalSnapshotConsensusState,
      status: CollectingSignatures,
      validSignatures: List[SignatureProof]
    ): F[Option[Transition]] =
      loggerBundle.app.withOrdinal(status.majorityArtifactInfo.artifact.ordinal) {
        HasherSelector[F].withCurrent { implicit hasher =>
          for {
            facilitatorsHash <- state.facilitators.value.hash
            facilitators = state.facilitators.value
            _ <- loggerBundle.consensus.roundFinished(facilitators)
            result <- NonEmptySet.fromSet(validSignatures.toSortedSet).traverse { signaturesNes =>
              val signedArtifact = Signed(status.majorityArtifactInfo.artifact, signaturesNes)
              // Use the artifact hash (agreed upon during Proposals phase) instead of signedArtifact.hash.
              // signedArtifact.hash includes signatures, which can differ across nodes when quorum < total
              // (e.g., some nodes collect 3 signatures, others 4), causing non-deterministic snapshotHash
              // and deadlocking the next round's Facility phase.
              val snapshotHash = status.majorityArtifactInfo.hash
              Transition(
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
              ).pure[F]
            }
          } yield result
        }
      }

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

    private val selfId: PeerId = PeerId.fromPublic(keyPair.getPublic)

    /** Spread proposal — only called by the leader. Uses direct push to all facilitators. */
    private def spreadProposal(
      state: GlobalSnapshotConsensusState,
      key: GlobalSnapshotKey,
      hash: Hash,
      facilitatorsHash: Hash,
      artifact: GlobalSnapshotArtifact,
      lastSnapshotHash: Hash
    ): F[Unit] = {
      val declaration = ConsensusPeerDeclaration(key, Proposal(hash, facilitatorsHash, lastSnapshotHash))
      val targets = state.facilitators.value.toSet

      gossip.spreadDirect(declaration, targets) >>
        gossip.spreadCommon(ConsensusArtifact(key, artifact))
    }

    private def spreadSignature(
      state: GlobalSnapshotConsensusState,
      key: GlobalSnapshotKey,
      signature: Signature,
      facilitatorsHash: Hash,
      lastSnapshotHash: Hash
    ): F[Unit] = {
      val declaration = ConsensusPeerDeclaration(key, MajoritySignature(signature, facilitatorsHash, lastSnapshotHash))
      gossip.spreadDirect(declaration, state.facilitators.value.toSet)
    }

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
        logger.error(s"[CONSENSUS] Cannot persist GlobalSnapshot ordinal=${signedArtifact.ordinal.show}") >> MonadThrow[F].raiseError(
          new RuntimeException("Persist failed")
        )
      )
    }

    private def checkForkByLastSnapshotHash[A](declarations: SortedMap[PeerId, A], ownHash: Hash)(
      implicit extract: A => Hash
    ): F[Unit] =
      recoverIfForking[F](ownHash, lastSnapshotHashObservationName, restartService, nodeStorage, leavingDelay)(
        declarations.map { case (pid, decl) => (pid, extract(decl)) }
      )

    private def checkForkByFacilitatorsHash[A](
      declarations: SortedMap[PeerId, A],
      ownHash: Hash
    )(extractHash: A => Hash): F[Unit] =
      recoverIfForking[F](ownHash, facilitatorsHashObservationName, restartService, nodeStorage, leavingDelay)(
        declarations.map { case (pid, decl) => (pid, extractHash(decl)) }
      )

    private def checkForkByConsensusConfigHash(facilities: SortedMap[PeerId, Facility]): F[Unit] = {
      val ownConfigHash = config.deterministicConfigHash
      val peerConfigHashes = facilities.flatMap {
        case (pid, f) => f.consensusConfigHash.map(pid -> _)
      }
      if (peerConfigHashes.nonEmpty)
        recoverIfForking[F](ownConfigHash, consensusConfigHashObservationName, restartService, nodeStorage, leavingDelay)(
          SortedMap.from(peerConfigHashes)
        )
      else Applicative[F].unit
    }

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

      // DAG L1 block/transaction data
      val allTransactions = signed.blocks.toList.flatMap(_.block.transactions.toList)
      val txCount = allTransactions.size
      val txAmountTotal = allTransactions.map(_.amount.value.value).sum
      val txFeeTotal = allTransactions.map(_.fee.value.value).sum

      // State channel data
      val scCount = signed.stateChannelSnapshots.values.map(_.size).sum
      val scAddressCount = signed.stateChannelSnapshots.size
      val allScBinaries = signed.stateChannelSnapshots.values.flatMap(_.toList)
      val scBinaryTotalBytes = allScBinaries.map(_.value.content.length.toLong).sum
      val scFeeTotal = allScBinaries.map(_.value.fee.value.value).sum

      // Rewards
      val rewardsCount = signed.rewards.size
      val rewardsAmountTotal = signed.rewards.toList.map(_.amount.value.value).sum
      val delegateRewardsCount = signed.delegateRewards.map(_.values.map(_.size).sum).getOrElse(0)

      // AllowSpend (swaps)
      val allowSpendBlocks = signed.allowSpendBlocks.map(_.toList).getOrElse(List.empty)
      val allowSpendBlocksCount = allowSpendBlocks.size
      val allAllowSpends = allowSpendBlocks.flatMap(_.transactions.toList)
      val allowSpendTxCount = allAllowSpends.size
      val allowSpendAmountTotal = allAllowSpends.map(_.amount.value.value).sum
      val allowSpendFeeTotal = allAllowSpends.map(_.fee.value.value).sum

      // TokenLock
      val tokenLockBlocks = signed.tokenLockBlocks.map(_.toList).getOrElse(List.empty)
      val tokenLockBlocksCount = tokenLockBlocks.size
      val allTokenLocks = tokenLockBlocks.flatMap(_.tokenLocks.toList)
      val tokenLockTxCount = allTokenLocks.size
      val tokenLockAmountTotal = allTokenLocks.map(_.amount.value.value).sum
      val tokenLockFeeTotal = allTokenLocks.map(_.fee.value.value).sum

      // Other fields
      val spendActionsCount = signed.spendActions.map(_.values.map(_.size).sum).getOrElse(0)
      val updateNodeParamsCount = signed.updateNodeParameters.map(_.size).getOrElse(0)
      val artifactsCount = signed.artifacts.map(_.size).getOrElse(0)
      val activeDelegatedStakesCount = signed.activeDelegatedStakes.map(_.values.map(_.size).sum).getOrElse(0)
      val delegatedStakesWithdrawalsCount = signed.delegatedStakesWithdrawals.map(_.values.map(_.size).sum).getOrElse(0)
      val activeNodeCollateralsCount = signed.activeNodeCollaterals.map(_.values.map(_.size).sum).getOrElse(0)
      val nodeCollateralWithdrawalsCount = signed.nodeCollateralWithdrawals.map(_.values.map(_.size).sum).getOrElse(0)

      val addressLabel: Metrics.LabelName = Metrics.unsafeLabelName("metagraph_address")

      val perAddressMetrics = signed.stateChannelSnapshots.toList.traverse_ {
        case (address, binaries) =>
          val addrTag: Metrics.TagSeq = Seq((addressLabel, address.show))
          val binariesCount = binaries.size
          val totalBytes = binaries.toList.map(_.value.content.length.toLong).sum
          val totalFee = binaries.toList.map(_.value.fee.value.value).sum

          Metrics[F].updateGauge("dag_global_snapshot_incremental_sc_address_binaries_count", binariesCount, addrTag) >>
            Metrics[F].updateGauge("dag_global_snapshot_incremental_sc_address_binary_bytes", totalBytes, addrTag) >>
            Metrics[F].updateGauge("dag_global_snapshot_incremental_sc_address_fee", totalFee, addrTag) >>
            Metrics[F].incrementCounterBy("dag_global_snapshot_sc_address_fee_cumulative", totalFee, addrTag) >>
            Metrics[F].incrementCounterBy("dag_global_snapshot_sc_address_bytes_cumulative", totalBytes, addrTag) >>
            // Per-metagraph last activity timestamp and submission count
            Async[F].realTimeInstant.map(_.getEpochSecond.toDouble).flatMap { nowEpochSecond =>
              Metrics[F].updateGauge("dag_global_snapshot_sc_address_last_activity_epoch", nowEpochSecond, addrTag) >>
                Metrics[F].incrementCounterBy("dag_global_snapshot_sc_address_submissions_count", binariesCount, addrTag)
            }
      }

      Metrics[F].updateGauge("dag_global_snapshot_ordinal", signed.ordinal.value) >>
        Metrics[F].updateGauge("dag_global_snapshot_height", signed.height.value) >>
        Metrics[F].updateGauge("dag_global_snapshot_signature_count", signed.proofs.size) >>
        Metrics[F].updateGauge("dag_global_snapshot_tips_count", deprecatedTips, Seq(("tip_type", "deprecated"))) >>
        Metrics[F].updateGauge("dag_global_snapshot_tips_count", activeTips, Seq(("tip_type", "active"))) >>
        Metrics[F].incrementCounterBy("dag_global_snapshot_blocks_total", signed.blocks.size) >>
        Metrics[F].incrementCounterBy("dag_global_snapshot_transactions_total", txCount) >>
        Metrics[F].incrementCounterBy("dag_global_snapshot_state_channel_snapshots_total", scCount) >>
        // Cumulative counters for value metrics (survive across scrapes unlike gauges)
        Metrics[F].incrementCounterBy("dag_global_snapshot_transaction_amount_cumulative", txAmountTotal) >>
        Metrics[F].incrementCounterBy("dag_global_snapshot_transaction_fee_cumulative", txFeeTotal) >>
        Metrics[F].incrementCounterBy("dag_global_snapshot_sc_fee_cumulative", scFeeTotal) >>
        Metrics[F].incrementCounterBy("dag_global_snapshot_sc_binary_bytes_cumulative", scBinaryTotalBytes) >>
        Metrics[F].incrementCounterBy("dag_global_snapshot_rewards_amount_cumulative", rewardsAmountTotal) >>
        Metrics[F].incrementCounterBy("dag_global_snapshot_allow_spend_amount_cumulative", allowSpendAmountTotal) >>
        Metrics[F].incrementCounterBy("dag_global_snapshot_allow_spend_fee_cumulative", allowSpendFeeTotal) >>
        Metrics[F].incrementCounterBy("dag_global_snapshot_token_lock_amount_cumulative", tokenLockAmountTotal) >>
        Metrics[F].incrementCounterBy("dag_global_snapshot_token_lock_fee_cumulative", tokenLockFeeTotal) >>
        // DAG L1 - blocks, transactions, amounts, fees
        Metrics[F].updateGauge("dag_global_snapshot_incremental_blocks_count", signed.blocks.size) >>
        Metrics[F].updateGauge("dag_global_snapshot_incremental_transactions_count", txCount) >>
        Metrics[F].updateGauge("dag_global_snapshot_incremental_transaction_amount_total", txAmountTotal) >>
        Metrics[F].updateGauge("dag_global_snapshot_incremental_transaction_fee_total", txFeeTotal) >>
        // State channel - counts, sizes, fees
        Metrics[F].updateGauge("dag_global_snapshot_incremental_sc_addresses_count", scAddressCount) >>
        Metrics[F].updateGauge("dag_global_snapshot_incremental_sc_binaries_count", scCount) >>
        Metrics[F].updateGauge("dag_global_snapshot_incremental_sc_binary_total_bytes", scBinaryTotalBytes) >>
        Metrics[F].updateGauge("dag_global_snapshot_incremental_sc_fee_total", scFeeTotal) >>
        // Rewards
        Metrics[F].updateGauge("dag_global_snapshot_incremental_rewards_count", rewardsCount) >>
        Metrics[F].updateGauge("dag_global_snapshot_incremental_rewards_amount_total", rewardsAmountTotal) >>
        Metrics[F].updateGauge("dag_global_snapshot_incremental_delegate_rewards_count", delegateRewardsCount) >>
        // AllowSpend (swaps)
        Metrics[F].updateGauge("dag_global_snapshot_incremental_allow_spend_blocks_count", allowSpendBlocksCount) >>
        Metrics[F].updateGauge("dag_global_snapshot_incremental_allow_spend_tx_count", allowSpendTxCount) >>
        Metrics[F].updateGauge("dag_global_snapshot_incremental_allow_spend_amount_total", allowSpendAmountTotal) >>
        Metrics[F].updateGauge("dag_global_snapshot_incremental_allow_spend_fee_total", allowSpendFeeTotal) >>
        // TokenLock
        Metrics[F].updateGauge("dag_global_snapshot_incremental_token_lock_blocks_count", tokenLockBlocksCount) >>
        Metrics[F].updateGauge("dag_global_snapshot_incremental_token_lock_tx_count", tokenLockTxCount) >>
        Metrics[F].updateGauge("dag_global_snapshot_incremental_token_lock_amount_total", tokenLockAmountTotal) >>
        Metrics[F].updateGauge("dag_global_snapshot_incremental_token_lock_fee_total", tokenLockFeeTotal) >>
        // Other fields
        Metrics[F].updateGauge("dag_global_snapshot_incremental_spend_actions_count", spendActionsCount) >>
        Metrics[F].updateGauge("dag_global_snapshot_incremental_update_node_params_count", updateNodeParamsCount) >>
        Metrics[F].updateGauge("dag_global_snapshot_incremental_artifacts_count", artifactsCount) >>
        Metrics[F].updateGauge("dag_global_snapshot_incremental_active_delegated_stakes_count", activeDelegatedStakesCount) >>
        Metrics[F].updateGauge("dag_global_snapshot_incremental_delegated_stakes_withdrawals_count", delegatedStakesWithdrawalsCount) >>
        Metrics[F].updateGauge("dag_global_snapshot_incremental_active_node_collaterals_count", activeNodeCollateralsCount) >>
        Metrics[F].updateGauge("dag_global_snapshot_incremental_node_collateral_withdrawals_count", nodeCollateralWithdrawalsCount) >>
        // Per-metagraph-address breakdown
        perAddressMetrics
    }
  }
}
