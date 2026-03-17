package io.constellationnetwork.dag.l0.infrastructure.snapshot

import java.security.KeyPair
import java.time.Instant

import cats.data.{NonEmptySet, StateT}
import cats.effect.{Async, Outcome, Ref}
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
import io.constellationnetwork.node.shared.domain.consensus.ConsensusFunctions.InvalidArtifact
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
import io.constellationnetwork.node.shared.infrastructure.snapshot.GlobalArtifactMismatch
import io.constellationnetwork.node.shared.infrastructure.snapshot.SnapshotConsensusFunctions.gossipForkInfo
import io.constellationnetwork.node.shared.logger.LoggerBundle
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.gossip.Ordinal
import io.constellationnetwork.schema.mpt.{GlobalStateKey, MptStore, MptStoreSavepoint}
import io.constellationnetwork.schema.peer.PeerId
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
  * '''Consensus Flow (Leader-Based Proposal Model)''':
  * {{{
  *   CollectingFacilities → CollectingProposals → CollectingSignatures → Finished
  * }}}
  *
  * '''Phase 1: CollectingFacilities → CollectingProposals'''
  *   - All peers declare a `Facility` with their upper bounds, candidates, and trigger type.
  *   - Once quorum is reached, EVERY peer (leader and followers) independently builds a proposal by calling `createProposalArtifact` from
  *     the same inputs.
  *   - The leader spreads its proposal hash + artifact via gossip.
  *   - Fork detection: peers verify facilitatorsHash and lastSnapshotHash match.
  *
  * '''Phase 2: CollectingProposals → CollectingSignatures'''
  *   - Followers compare their locally-built artifact hash to the leader's proposal:
  *     - '''Match''': Use local ArtifactInfo directly (fast path, no extra validation).
  *     - '''Mismatch''': Re-validate the leader's artifact via `validateArtifact` (full recompute). This mutates MptStore; a savepoint is
  *       taken before and restored on failure.
  *     - '''Validation failure''': Follower withdraws from the round. The leader's artifact stays in resources, but a guard
  *       (`alreadyWithdrawn`) prevents hot-loop re-entry.
  *   - On success, the peer signs the agreed artifact hash.
  *
  * '''Phase 3: CollectingSignatures → Finished'''
  *   - Quorum of valid signatures collected.
  *   - `snapshotHash` uses the artifact hash (agreed in Phase 2), NOT the signed artifact hash, to avoid non-determinism from different
  *     signature counts per node.
  *
  * '''Determinism guarantees''':
  *   - All event lists are sorted before processing (see `GlobalSnapshotConsensusFunctions`).
  *   - Facilitators are derived from facility declarations (deterministic), not from gossip proofs.
  *   - Penalty tracking uses `SortedMap` for deterministic iteration.
  *   - MptStore mutations are protected by savepoint/restore on validation failure.
  *
  * @see
  *   ConsensusStateAdvancer for the generic interface
  * @see
  *   GlobalSnapshotConsensusFunctions for proposal creation and validation
  * @see
  *   GlobalSnapshotAcceptanceManager for the acceptance pipeline
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
    loggerBundle: LoggerBundle[F],
    mptStore: MptStore[F, GlobalStateKey]
  ): GlobalSnapshotConsensusStateAdvancer[F] = new GlobalSnapshotConsensusStateAdvancer[F] {

    private val logger: SelfAwareStructuredLogger[F] = Slf4jLogger.getLoggerFromClass[F](getClass)
    private val lastSnapshotHashObservationName = "last-snapshot-hash"
    private val facilitatorsHashObservationName = "facilitators-hash"
    private val consensusConfigHashObservationName = "consensus-config-hash"

    /** Savepoint taken before `createArtifact()` mutations. On round abandonment + retry at the same ordinal, this is restored before
      * re-building the proposal to ensure the MptStore starts from a clean pre-mutation state.
      *
      * Tracks the key (ordinal) alongside the savepoint so that stale savepoints from a different ordinal
      * (e.g., after recovery download) are discarded instead of restored — restoring a savepoint from
      * ordinal N into an MptStore that was replaced by a download at ordinal M would corrupt state.
      */
    private val proposalSavepointRef: Ref[F, Option[(GlobalSnapshotKey, MptStoreSavepoint[F])]] = Ref.unsafe(none)

    protected val clusterStorage: ClusterStorage[F] = clusterStorageInstance
    protected val config: ConsensusConfig = consensusConfig

    private case class Transition(newState: GlobalSnapshotConsensusState, sideEffect: F[Unit])

    def getConsensusOutcome(
      state: GlobalSnapshotConsensusState
    ): Option[(Previous[GlobalSnapshotKey], GlobalConsensusOutcome)] =
      state.status match {
        case f: Finished =>
          // Compute consensus-agreed peer quality and removal penalties from PROOFS ONLY.
          // CRITICAL: We derive both "completed" and "removed" from the signed artifact's proofs
          // (who actually signed), NOT from removedFacilitators/withdrawnFacilitators. The proofs
          // are embedded in the consensus-agreed artifact and are identical across all nodes.
          //
          // removedFacilitators includes BOTH:
          //   1. Fork-evicted peers (deterministic — based on quorum facility declarations)
          //   2. View-change-evicted peers (NON-deterministic — based on local stall detection timing)
          // Using removedFacilitators for penalties would cause different nodes to compute different
          // penalty maps → different facilitator exclusions → different facilitator sets → fork.
          //
          // Instead, we use: nonSigners = facilitators - signers (proofs).
          // This is fully deterministic: both facilitators and proofs are consensus-agreed.
          val signers = f.signedMajorityArtifact.proofs.map(_.id.toPeerId).toSortedSet
          val nonSigners = state.facilitators.value.filterNot(signers.contains).toSet

          // Compute removal penalties: decrement previous, add penalties for non-signers.
          // Uses SortedMap for deterministic iteration when filtering penalized peers.
          val previousPenalties = state.lastOutcome.removalPenalties
          val decrementedPenalties = previousPenalties.view.mapValues(_ - 1).filter(_._2 > 0).to(SortedMap)
          val newPenalties = nonSigners.foldLeft(decrementedPenalties) { (acc, pid) =>
            acc.updated(pid, config.removalPenaltyRounds)
          }
          val finalPenalties = if (config.removalPenaltyRounds > 0) newPenalties else SortedMap.empty[PeerId, Int]
          val thisRoundQuality: SortedMap[PeerId, (Int, Int)] = SortedMap.from(
            state.facilitators.value.map { pid =>
              val completed = if (signers.contains(pid)) 1 else 0
              pid -> (completed, 1)
            }
          )
          // Accumulate with previous rounds: merge (completed, participated) tuples.
          // Apply deterministic decay when any counter exceeds the threshold to prevent unbounded growth.
          // Halving preserves relative quality ordering while keeping counters bounded.
          // After decay, prune entries where both counters are 0 (departed peers with no history).
          val rawAccumulated: SortedMap[PeerId, (Int, Int)] = {
            val previous = state.lastOutcome.peerQuality
            val allPeerIds = (previous.keySet.toList ::: thisRoundQuality.keySet.toList).distinct
            SortedMap.from(allPeerIds.map { pid =>
              val (pc, pp) = previous.getOrElse(pid, (0, 0))
              val (tc, tp) = thisRoundQuality.getOrElse(pid, (0, 0))
              pid -> (pc + tc, pp + tp)
            })
          }
          val needsDecay = rawAccumulated.values.exists { case (_, p) => p > config.qualityDecayThreshold }
          val decayed = if (needsDecay) rawAccumulated.view.mapValues { case (c, p) => (c / 2, p / 2) }.to(SortedMap)
          else rawAccumulated
          val accumulatedQuality = decayed.filter { case (_, (c, p)) => c > 0 || p > 0 }

          val outcome = GlobalConsensusOutcome(
            state.key,
            state.facilitators,
            state.removedFacilitators,
            state.withdrawnFacilitators,
            state.eligibleFacilitators,
            Finished(f.signedMajorityArtifact, f.context, f.majorityTrigger, f.candidates, f.facilitatorsHash, f.snapshotHash),
            removalPenalties = finalPenalties,
            peerQuality = accumulatedQuality
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

    /** Advances from Facilities to Proposals once quorum facility declarations are collected.
      *
      * All peers independently build the proposal artifact from the same events. The leader then spreads its proposal; followers compare
      * hashes in the next phase. Fork detection verifies facilitatorsHash, lastSnapshotHash, and consensusConfigHash match across peers.
      */
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
            // NOTE: facilitatorsHash fork check is handled by identifyForkedPeers below (evicts minority
            // instead of killing this node). Do NOT call checkForkByFacilitatorsHash here — after stall-based
            // eviction, different nodes may legitimately have different facilitator sets, which would cause
            // cascading false-positive fork detections and kill all nodes.
            _ <- maybeFacilities.traverse_(checkForkByLastSnapshotHash(_, status.lastSnapshotHash))
            _ <- maybeFacilities.traverse_(checkForkByConsensusConfigHash)

            // Evict peers with minority facilitatorsHash — deterministic since all healthy nodes
            // see the same declarations and identify the same minority.
            cleanFacilities = maybeFacilities.map { facilities =>
              val forkedPeers = ConsensusStateUpdater.identifyForkedPeers(
                status.facilitatorsHash,
                facilities.map { case (pid, f) => (pid, f.facilitatorsHash) }
              )
              if (forkedPeers.nonEmpty) facilities -- forkedPeers else facilities
            }

            _ <- (maybeFacilities, cleanFacilities).tupled.traverse_ {
              case (original, clean) =>
                val evicted = original.keySet -- clean.keySet
                ConsensusLog
                  .warn(
                    logger,
                    ConsensusLog.Fork,
                    state.key.show,
                    "n/a",
                    "event" -> "FORKED_PEERS_EVICTED",
                    "evicted" -> evicted.size.toString,
                    "remaining" -> clean.size.toString,
                    "evictedPeers" -> evicted.toList.map(ConsensusLog.pid).mkString(",")
                  )
                  .whenA(evicted.nonEmpty)
            }

            _ <- cleanFacilities.traverse_ { _ =>
              ConsensusLog.debug(
                logger,
                ConsensusLog.Fork,
                state.key.show,
                "n/a",
                "event" -> "FORK_CHECKS_PASSED",
                "facilitatorsHash" -> status.facilitatorsHash.show.take(8),
                "lastSnapshotHash" -> status.lastSnapshotHash.show.take(8)
              )
            }

            result <- cleanFacilities.flatTraverse { facilities =>
              // Update state to reflect eviction before proceeding to proposals
              val evictedPeers = state.facilitators.value.filterNot(facilities.contains).toSet
              val updatedState: GlobalSnapshotConsensusState =
                if (evictedPeers.nonEmpty)
                  state.copy[GlobalSnapshotKey, GlobalSnapshotStatus, GlobalConsensusOutcome, GlobalConsensusKind](
                    facilitators = Facilitators(state.facilitators.value.filter(facilities.contains)),
                    removedFacilitators = RemovedFacilitators(state.removedFacilitators.value ++ evictedPeers)
                  )
                else state
              toProposalsPhase(updatedState, facilities)
            }
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

        // Restore any previous savepoint from an abandoned round at the SAME ordinal,
        // ensuring MptStore is in a clean pre-mutation state before createArtifact().
        // CRITICAL: Only restore if the savepoint was taken for this exact key. After a recovery
        // download, the MptStore has been completely replaced with fresh state — restoring a stale
        // savepoint from a different ordinal would revert the MptStore to pre-download state,
        // corrupting all subsequent rounds.
        previousSp <- proposalSavepointRef.getAndSet(none)
        _ <- previousSp.traverse_ { case (spKey, sp) =>
          if (spKey === state.key)
            sp.restore >>
              ConsensusLog.info(logger, ConsensusLog.Lifecycle, state.key.show, "n/a", "event" -> "MPT_SAVEPOINT_RESTORED")
          else
            ConsensusLog.warn(
              logger,
              ConsensusLog.Lifecycle,
              state.key.show,
              "n/a",
              "event" -> "MPT_SAVEPOINT_DISCARDED_WRONG_KEY",
              "savepointKey" -> spKey.show,
              "currentKey" -> state.key.show
            )
        }
        // Take a fresh savepoint before mutations. If this round is abandoned and retried,
        // the next buildProposalTransition will restore this savepoint.
        sp <- mptStore.savepoint
        _ <- proposalSavepointRef.set((state.key, sp).some)

        (artifact, context, returnedEvents) <- createArtifact(state, majorityTrigger, extractEvents(peerEvents))

        _ <- storeReturnedEvents(peerEvents, returnedEvents)
        hash <- hashArtifact(artifact)
        _ <- checkFollowerExit(state)
        isLeader = selfId === state.leader
        role = if (isLeader) "LEADER" else "FOLLOWER"
        withdrawnCount = state.withdrawnFacilitators.value.size
        _ <- ConsensusLog.info(
          logger,
          ConsensusLog.Phase,
          state.key.show,
          role,
          (Seq(
            "event" -> "FACILITIES_TO_PROPOSALS",
            "ordinal" -> artifact.ordinal.show,
            "trigger" -> majorityTrigger.toString,
            "hash" -> hash.show.take(8),
            "facilitators" -> state.facilitators.value.size.toString,
            "candidates" -> candidates.size.toString,
            "leader" -> ConsensusLog.pid(state.leader),
            "self" -> ConsensusLog.pid(selfId),
            "view" -> state.viewNumber.toString,
            "facilitatorsHash" -> facilitatorsHash.show.take(8),
            "lastSnapshotHash" -> state.lastOutcome.finished.snapshotHash.show.take(8)
          ) ++ (if (withdrawnCount > 0) Seq("withdrawn" -> withdrawnCount.toString) else Seq.empty)): _*
        )
        _ <- ConsensusLog.info(
          logger,
          ConsensusLog.Proposal,
          state.key.show,
          role,
          "event" -> "PROPOSAL_STATE_PROOF",
          "detail" -> describeStateProof(artifact.stateProof)
        )
        _ <- ConsensusLog.info(
          logger,
          ConsensusLog.Proposal,
          state.key.show,
          role,
          "event" -> "PROPOSAL_CONTEXT_DIGEST",
          "detail" -> contextDigest(context)
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

    /** Advances from Proposals to Signatures by resolving the leader's proposal.
      *
      * '''Leader-based proposal resolution''': Only the leader spreads a Proposal + ConsensusArtifact. Non-leaders wait for the leader's
      * proposal via gossip, then either:
      *   - Use their local ArtifactInfo if hashes match (fast path), or
      *   - Re-validate the leader's artifact via full recompute (slow path).
      *
      * '''Hot-loop guard''': If this peer already withdrew from this round, it skips re-entry. Without this guard, a validation failure
      * (which returns `none[Transition]`) would cause the leader's proposal (still in resources) to re-trigger validation on every
      * `checkUpdate`.
      *
      * '''MptStore safety''': The slow path takes an MptStore savepoint before validation and restores it on failure. This prevents partial
      * state from cascading to future rounds.
      */
    private def advanceFromProposals(
      state: GlobalSnapshotConsensusState,
      status: CollectingProposals,
      resources: ConsensusResources[GlobalSnapshotArtifact, GlobalConsensusKind]
    ): F[Option[Transition]] =
      loggerBundle.app.withOrdinal(status.proposalArtifactInfo.artifact.ordinal) {
        HasherSelector[F].withCurrent { implicit hasher =>
          // Guard: if we already withdrew from this round, don't re-enter validation.
          // Without this, a validation failure (which returns none[Transition]) causes a hot loop:
          // the leader's proposal stays in resources, so every checkUpdate re-enters here,
          // re-validates, re-fails, and re-withdraws (7+/sec observed in production).
          val alreadyWithdrawn =
            resources.withdrawalsMap.get(selfId).contains(GlobalConsensusKind.Signature: GlobalConsensusKind) ||
              state.withdrawnFacilitators.value.contains(selfId)

          if (alreadyWithdrawn)
            none[Transition].pure[F]
          else {
            val leader = state.leader
            val maybeLeaderProposal = resources.peerDeclarationsMap.get(leader).flatMap(_.proposal)

            maybeLeaderProposal match {
              case Some(leaderProposal) =>
                for {
                  _ <- loggerBundle.consensus.collectingProposals(List(leader))
                  // Skip facilitatorsHash fork check when view > 0 (eviction happened) — different
                  // nodes may have different facilitator sets after stall-based eviction.
                  _ <- checkForkByFacilitatorsHash(
                    SortedMap(leader -> leaderProposal),
                    status.facilitatorsHash
                  )(_.facilitatorsHash).whenA(state.viewNumber === 0)
                  _ <- checkForkByLastSnapshotHash(
                    SortedMap(leader -> leaderProposal),
                    status.lastSnapshotHash
                  )
                  result <- resolveLeaderProposal(state, status, resources, leaderProposal)
                } yield result
              case None =>
                if (selfId === state.leader)
                  // Leader (possibly after view change) — spread proposal so peers can advance
                  ConsensusLog.info(
                    logger,
                    ConsensusLog.Phase,
                    state.key.show,
                    "Leader",
                    "event" -> "PROPOSAL_RESPREAD",
                    "hash" -> status.proposalArtifactInfo.hash.show.take(8),
                    "targets" -> state.facilitators.value.size.toString,
                    "view" -> state.viewNumber.toString
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
        ConsensusLog.info(
          logger,
          ConsensusLog.Validation,
          state.key.show,
          role,
          "event" -> "ARTIFACT_HASH_MATCH",
          "hash" -> leaderProposal.hash.show.take(12),
          "match" -> "true"
        ) >>
          ConsensusLog.info(
            logger,
            ConsensusLog.Phase,
            state.key.show,
            role,
            "event" -> "PROPOSALS_TO_SIGNATURES",
            "matchesOwn" -> "true",
            "hash" -> leaderProposal.hash.show.take(8),
            "trigger" -> status.majorityTrigger.toString,
            "leader" -> ConsensusLog.pid(state.leader),
            "view" -> state.viewNumber.toString
          ) >>
          Metrics[F].incrementCounter("dag_consensus_proposal_affinity_match") >>
          buildSignatureTransition(state, status, status.proposalArtifactInfo, List(leaderProposal.hash)).map(_.some)
      } else {
        // Leader proposed a different artifact — apply it via the follower path.
        // createContext (follower path) mutates the shared MptStore.
        // We take a savepoint so we can restore on IO-level failure to prevent
        // partial state from cascading to future rounds.
        resources.artifacts.get(leaderProposal.hash) match {
          case Some(leaderArtifact) =>
            mptStore.savepoint.flatMap { sp =>
              val validate =
                ConsensusLog.info(
                  logger,
                  ConsensusLog.Validation,
                  state.key.show,
                  "Validator",
                  "event" -> "VALIDATING_LEADER_ARTIFACT",
                  "leaderHash" -> leaderProposal.hash.show.take(8),
                  "ownHash" -> status.proposalArtifactInfo.hash.show.take(8)
                ) >>
                  validateLeaderArtifact(state, status, leaderArtifact, leaderProposal.hash).flatMap {
                    case Right(leaderInfo) =>
                      // Validation succeeded — MptStore mutations are correct, keep them
                      ConsensusLog.info(
                        logger,
                        ConsensusLog.Validation,
                        state.key.show,
                        role,
                        "event" -> "ARTIFACT_REVALIDATED",
                        "matchesOwn" -> "false",
                        "leaderHash" -> leaderProposal.hash.show.take(8),
                        "ownHash" -> status.proposalArtifactInfo.hash.show.take(8),
                        "trigger" -> status.majorityTrigger.toString,
                        "leader" -> ConsensusLog.pid(state.leader),
                        "view" -> state.viewNumber.toString
                      ) >>
                        Metrics[F].incrementCounter("dag_consensus_proposal_affinity_mismatch_accepted") >>
                        buildSignatureTransition(state, status, leaderInfo, List(leaderProposal.hash)).map(_.some)
                    case Left(invalidArtifact) =>
                      // Validation failed — restore MptStore to pre-validation state
                      val diffDetail = describeInvalidArtifact(invalidArtifact)
                      val ownCtx = status.proposalArtifactInfo.context
                      val ctxDigest = contextDigest(ownCtx)
                      sp.restore >>
                        ConsensusLog.warn(
                          logger,
                          ConsensusLog.Validation,
                          state.key.show,
                          role,
                          "event" -> "VALIDATION_FAILED",
                          "leaderHash" -> leaderProposal.hash.show.take(8),
                          "ownHash" -> status.proposalArtifactInfo.hash.show.take(8),
                          "leader" -> ConsensusLog.pid(state.leader),
                          "view" -> state.viewNumber.toString,
                          "reason" -> diffDetail
                        ) >>
                        ConsensusLog.info(
                          logger,
                          ConsensusLog.Validation,
                          state.key.show,
                          role,
                          "event" -> "OWN_CONTEXT_DIGEST",
                          "detail" -> ctxDigest
                        ) >>
                        ConsensusLog.info(
                          logger,
                          ConsensusLog.Phase,
                          state.key.show,
                          role,
                          "event" -> "WITHDRAW_VALIDATION_FAIL",
                          "reason" -> "proposal_validation_failed",
                          "mptStoreRestored" -> "true"
                        ) >>
                        gossip.spread(ConsensusWithdrawPeerDeclaration(state.key, GlobalConsensusKind.Signature: GlobalConsensusKind)) >>
                        Metrics[F].incrementCounter("dag_consensus_proposal_validation_failure") >>
                        Metrics[F].incrementCounter("dag_consensus_withdrawal_sent") >>
                        none[Transition].pure[F]
                  }

              // Use guaranteeCase to restore MptStore on any unexpected exception.
              // Without this, an IO-level failure in validateLeaderArtifact would skip
              // sp.restore, leaving partial MptStore state that poisons future rounds.
              Async[F].guaranteeCase(validate) {
                case Outcome.Errored(_) | Outcome.Canceled() =>
                  sp.restore >>
                    ConsensusLog.error(logger, ConsensusLog.Lifecycle, state.key.show, role, "event" -> "MPT_RESTORED_AFTER_FAILURE")
                case Outcome.Succeeded(_) =>
                  Applicative[F].unit
              }
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
    )(implicit hasher: Hasher[F]): F[Either[InvalidArtifact, ArtifactInfo[GlobalSnapshotArtifact, GlobalSnapshotContext]]] =
      state.lastOutcome.finished.signedMajorityArtifact.toHashed.flatMap { hashedLast =>
        // Re-derive the artifact locally and compare it to the leader's proposal.
        // validateArtifact already derives the trigger from artifact.epochProgress (not from
        // status.majorityTrigger), so trigger divergence cannot cause a false mismatch.
        // The full recompute-and-compare approach is kept so that validators can reject
        // a malicious or buggy leader artifact before signing it.
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
              ArtifactInfo(validatedArtifact, context, hash).asRight[InvalidArtifact]
            case Left(err) =>
              err.asLeft[ArtifactInfo[GlobalSnapshotArtifact, GlobalSnapshotContext]]
          }
      }

    /** Produces a human-readable description of why the leader's artifact failed validation. */
    private def describeInvalidArtifact(err: InvalidArtifact): String = err match {
      case GlobalArtifactMismatch(leader, own) =>
        val diffs = List.newBuilder[String]
        if (leader.ordinal =!= own.ordinal) diffs += s"ordinal(leader=${leader.ordinal.show},own=${own.ordinal.show})"
        if (leader.height =!= own.height) diffs += s"height(leader=${leader.height.show},own=${own.height.show})"
        if (leader.subHeight =!= own.subHeight) diffs += s"subHeight(leader=${leader.subHeight.show},own=${own.subHeight.show})"
        if (leader.lastSnapshotHash =!= own.lastSnapshotHash)
          diffs += s"lastSnapshotHash(leader=${leader.lastSnapshotHash.show.take(8)},own=${own.lastSnapshotHash.show.take(8)})"
        if (leader.blocks.size != own.blocks.size) diffs += s"blocks(leader=${leader.blocks.size},own=${own.blocks.size})"
        if (leader.stateChannelSnapshots.size != own.stateChannelSnapshots.size)
          diffs += s"stateChannels(leader=${leader.stateChannelSnapshots.size},own=${own.stateChannelSnapshots.size})"
        val leaderScAddrs = leader.stateChannelSnapshots.keySet
        val ownScAddrs = own.stateChannelSnapshots.keySet
        val onlyLeader = leaderScAddrs -- ownScAddrs
        val onlyOwn = ownScAddrs -- leaderScAddrs
        if (onlyLeader.nonEmpty) diffs += s"scOnlyInLeader=[${onlyLeader.toList.map(_.show.take(8)).mkString(",")}]"
        if (onlyOwn.nonEmpty) diffs += s"scOnlyInOwn=[${onlyOwn.toList.map(_.show.take(8)).mkString(",")}]"
        if (leader.rewards =!= own.rewards) {
          diffs += s"rewards(leader=${leader.rewards.size},own=${own.rewards.size})"
          val onlyInLeader = leader.rewards -- own.rewards
          val onlyInOwn = own.rewards -- leader.rewards
          if (onlyInLeader.nonEmpty)
            diffs += s"rewardsOnlyInLeader=[${onlyInLeader.toList.map(r => s"${r.destination.show.take(8)}:${r.amount.value.value}").mkString(",")}]"
          if (onlyInOwn.nonEmpty)
            diffs += s"rewardsOnlyInOwn=[${onlyInOwn.toList.map(r => s"${r.destination.show.take(8)}:${r.amount.value.value}").mkString(",")}]"
        }
        if (leader.epochProgress =!= own.epochProgress)
          diffs += s"epochProgress(leader=${leader.epochProgress.show},own=${own.epochProgress.show})"
        if (leader.tips =!= own.tips) diffs += "tipsDiffer"
        if (leader.stateProof =!= own.stateProof) {
          val lp = leader.stateProof
          val op = own.stateProof
          val spDiffs = List.newBuilder[String]
          if (lp.lastStateChannelSnapshotHashesProof =!= op.lastStateChannelSnapshotHashesProof)
            spDiffs += s"scHashesProof(l=${lp.lastStateChannelSnapshotHashesProof.show.take(8)},o=${op.lastStateChannelSnapshotHashesProof.show
                .take(8)})"
          if (lp.lastTxRefsProof =!= op.lastTxRefsProof)
            spDiffs += s"txRefsProof(l=${lp.lastTxRefsProof.show.take(8)},o=${op.lastTxRefsProof.show.take(8)})"
          if (lp.balancesProof =!= op.balancesProof)
            spDiffs += s"balancesProof(l=${lp.balancesProof.show.take(8)},o=${op.balancesProof.show.take(8)})"
          if (lp.lastCurrencySnapshotsProof =!= op.lastCurrencySnapshotsProof)
            spDiffs += "currencySnapshotsProof"
          if (lp.activeAllowSpends =!= op.activeAllowSpends)
            spDiffs += s"activeAllowSpends(l=${lp.activeAllowSpends.map(_.show.take(8))},o=${op.activeAllowSpends.map(_.show.take(8))})"
          if (lp.activeTokenLocks =!= op.activeTokenLocks)
            spDiffs += s"activeTokenLocks(l=${lp.activeTokenLocks.map(_.show.take(8))},o=${op.activeTokenLocks.map(_.show.take(8))})"
          if (lp.tokenLockBalances =!= op.tokenLockBalances)
            spDiffs += s"tokenLockBalances(l=${lp.tokenLockBalances.map(_.show.take(8))},o=${op.tokenLockBalances.map(_.show.take(8))})"
          if (lp.lastAllowSpendRefs =!= op.lastAllowSpendRefs)
            spDiffs += s"lastAllowSpendRefs(l=${lp.lastAllowSpendRefs.map(_.show.take(8))},o=${op.lastAllowSpendRefs.map(_.show.take(8))})"
          if (lp.lastTokenLockRefs =!= op.lastTokenLockRefs)
            spDiffs += s"lastTokenLockRefs(l=${lp.lastTokenLockRefs.map(_.show.take(8))},o=${op.lastTokenLockRefs.map(_.show.take(8))})"
          if (lp.updateNodeParameters =!= op.updateNodeParameters)
            spDiffs += s"updateNodeParams(l=${lp.updateNodeParameters.map(_.show.take(8))},o=${op.updateNodeParameters.map(_.show.take(8))})"
          if (lp.activeDelegatedStakes =!= op.activeDelegatedStakes)
            spDiffs += s"activeDelegatedStakes(l=${lp.activeDelegatedStakes
                .map(_.show.take(8))},o=${op.activeDelegatedStakes.map(_.show.take(8))})"
          if (lp.delegatedStakesWithdrawals =!= op.delegatedStakesWithdrawals)
            spDiffs += s"delegatedStakesWithdrawals(l=${lp.delegatedStakesWithdrawals.map(_.show.take(8))},o=${op.delegatedStakesWithdrawals
                .map(_.show.take(8))})"
          if (lp.activeNodeCollaterals =!= op.activeNodeCollaterals)
            spDiffs += s"activeNodeCollaterals(l=${lp.activeNodeCollaterals
                .map(_.show.take(8))},o=${op.activeNodeCollaterals.map(_.show.take(8))})"
          if (lp.nodeCollateralWithdrawals =!= op.nodeCollateralWithdrawals)
            spDiffs += s"nodeCollateralWithdrawals(l=${lp.nodeCollateralWithdrawals.map(_.show.take(8))},o=${op.nodeCollateralWithdrawals
                .map(_.show.take(8))})"
          if (lp.priceState =!= op.priceState)
            spDiffs += s"priceState(l=${lp.priceState.map(_.show.take(8))},o=${op.priceState.map(_.show.take(8))})"
          if (lp.lastGlobalSnapshotsWithCurrency =!= op.lastGlobalSnapshotsWithCurrency)
            spDiffs += s"lastGlobalSnapshotsWithCurrency(l=${lp.lastGlobalSnapshotsWithCurrency.map(_.show.take(8))},o=${op.lastGlobalSnapshotsWithCurrency
                .map(_.show.take(8))})"
          if (lp.mptRoot =!= op.mptRoot)
            spDiffs += s"mptRoot(l=${lp.mptRoot.map(_.show.take(8))},o=${op.mptRoot.map(_.show.take(8))})"
          val spResult = spDiffs.result()
          if (spResult.isEmpty) diffs += "stateProofDiffers(no sub-field diff — possible serialization difference)"
          else diffs += s"stateProofDiffers{${spResult.mkString(",")}}"
        }
        if (leader.nextFacilitators =!= own.nextFacilitators)
          diffs += s"nextFacilitators(leader=${leader.nextFacilitators.size},own=${own.nextFacilitators.size})"
        if (leader.delegateRewards =!= own.delegateRewards) {
          val leaderDR = leader.delegateRewards.map(_.size).getOrElse(0)
          val ownDR = own.delegateRewards.map(_.size).getOrElse(0)
          diffs += s"delegateRewards(leader=$leaderDR,own=$ownDR)"
        }
        val leaderAllowSpend = leader.allowSpendBlocks.map(_.size).getOrElse(0)
        val ownAllowSpend = own.allowSpendBlocks.map(_.size).getOrElse(0)
        if (leaderAllowSpend != ownAllowSpend) diffs += s"allowSpendBlocks(leader=$leaderAllowSpend,own=$ownAllowSpend)"
        val leaderTokenLock = leader.tokenLockBlocks.map(_.size).getOrElse(0)
        val ownTokenLock = own.tokenLockBlocks.map(_.size).getOrElse(0)
        if (leaderTokenLock != ownTokenLock) diffs += s"tokenLockBlocks(leader=$leaderTokenLock,own=$ownTokenLock)"
        if (leader.spendActions =!= own.spendActions) {
          val leaderSA = leader.spendActions.map(_.size).getOrElse(0)
          val ownSA = own.spendActions.map(_.size).getOrElse(0)
          diffs += s"spendActions(leader=$leaderSA,own=$ownSA)"
        }
        if (leader.activeDelegatedStakes =!= own.activeDelegatedStakes) {
          val leaderADS = leader.activeDelegatedStakes.map(_.size).getOrElse(0)
          val ownADS = own.activeDelegatedStakes.map(_.size).getOrElse(0)
          diffs += s"activeDelegatedStakes(leader=$leaderADS,own=$ownADS)"
        }
        if (leader.delegatedStakesWithdrawals =!= own.delegatedStakesWithdrawals) {
          val leaderDSW = leader.delegatedStakesWithdrawals.map(_.size).getOrElse(0)
          val ownDSW = own.delegatedStakesWithdrawals.map(_.size).getOrElse(0)
          diffs += s"delegatedStakesWithdrawals(leader=$leaderDSW,own=$ownDSW)"
        }
        if (leader.activeNodeCollaterals =!= own.activeNodeCollaterals) {
          val leaderANC = leader.activeNodeCollaterals.map(_.size).getOrElse(0)
          val ownANC = own.activeNodeCollaterals.map(_.size).getOrElse(0)
          diffs += s"activeNodeCollaterals(leader=$leaderANC,own=$ownANC)"
        }
        if (leader.nodeCollateralWithdrawals =!= own.nodeCollateralWithdrawals) {
          val leaderNCW = leader.nodeCollateralWithdrawals.map(_.size).getOrElse(0)
          val ownNCW = own.nodeCollateralWithdrawals.map(_.size).getOrElse(0)
          diffs += s"nodeCollateralWithdrawals(leader=$leaderNCW,own=$ownNCW)"
        }
        if (leader.updateNodeParameters =!= own.updateNodeParameters) {
          val leaderUNP = leader.updateNodeParameters.map(_.size).getOrElse(0)
          val ownUNP = own.updateNodeParameters.map(_.size).getOrElse(0)
          diffs += s"updateNodeParameters(leader=$leaderUNP,own=$ownUNP)"
        }
        if (leader.version =!= own.version)
          diffs += s"version(leader=${leader.version.show},own=${own.version.show})"
        val result = diffs.result()
        if (result.isEmpty) "GlobalArtifactMismatch(no field-level diff detected — possible serialization difference)"
        else s"GlobalArtifactMismatch[${result.mkString(",")}]"
      case other =>
        other.getClass.getSimpleName
    }

    /** Produces a compact representation of all stateProof sub-field hash prefixes for comparing leader vs follower. */
    private def describeStateProof(sp: GlobalSnapshotStateProof): String = {
      val parts = List.newBuilder[String]
      parts += s"scHashes=${sp.lastStateChannelSnapshotHashesProof.show.take(8)}"
      parts += s"txRefs=${sp.lastTxRefsProof.show.take(8)}"
      parts += s"balances=${sp.balancesProof.show.take(8)}"
      parts += s"currSnapshotsProof=${sp.lastCurrencySnapshotsProof.map(_.show.take(8)).getOrElse("none")}"
      parts += s"allowSpends=${sp.activeAllowSpends.map(_.show.take(8)).getOrElse("none")}"
      parts += s"tokenLocks=${sp.activeTokenLocks.map(_.show.take(8)).getOrElse("none")}"
      parts += s"tokenLockBal=${sp.tokenLockBalances.map(_.show.take(8)).getOrElse("none")}"
      parts += s"allowSpendRefs=${sp.lastAllowSpendRefs.map(_.show.take(8)).getOrElse("none")}"
      parts += s"tokenLockRefs=${sp.lastTokenLockRefs.map(_.show.take(8)).getOrElse("none")}"
      parts += s"nodeParams=${sp.updateNodeParameters.map(_.show.take(8)).getOrElse("none")}"
      parts += s"delegStakes=${sp.activeDelegatedStakes.map(_.show.take(8)).getOrElse("none")}"
      parts += s"delegWithdrawals=${sp.delegatedStakesWithdrawals.map(_.show.take(8)).getOrElse("none")}"
      parts += s"nodeCollaterals=${sp.activeNodeCollaterals.map(_.show.take(8)).getOrElse("none")}"
      parts += s"collateralWithdrawals=${sp.nodeCollateralWithdrawals.map(_.show.take(8)).getOrElse("none")}"
      parts += s"priceState=${sp.priceState.map(_.show.take(8)).getOrElse("none")}"
      parts += s"globalSnapsWithCurrency=${sp.lastGlobalSnapshotsWithCurrency.map(_.show.take(8)).getOrElse("none")}"
      parts += s"mptRoot=${sp.mptRoot.map(_.show.take(8)).getOrElse("none")}"
      parts.result().mkString(" ")
    }

    /** Produces a compact digest of GlobalSnapshotInfo field sizes/counts for diagnostic logging. Does NOT log actual data (state can be
      * 90MB+), only counts and hash prefixes of the stateProof.
      */
    private def contextDigest(ctx: GlobalSnapshotContext): String = {
      val parts = List.newBuilder[String]
      parts += s"scHashes=${ctx.lastStateChannelSnapshotHashes.size}"
      parts += s"txRefs=${ctx.lastTxRefs.size}"
      parts += s"balances=${ctx.balances.size}"
      parts += s"currencySnapshots=${ctx.lastCurrencySnapshots.size}"
      parts += s"currencyProofs=${ctx.lastCurrencySnapshotsProofs.size}"
      parts += s"allowSpends=${ctx.activeAllowSpends.map(_.values.map(_.values.map(_.size).sum).sum).getOrElse(0)}"
      parts += s"tokenLocks=${ctx.activeTokenLocks.map(_.values.map(_.size).sum).getOrElse(0)}"
      parts += s"tokenLockBal=${ctx.tokenLockBalances.map(_.size).getOrElse(0)}"
      parts += s"delegStakes=${ctx.activeDelegatedStakes.map(_.size).getOrElse(0)}"
      parts += s"delegWithdrawals=${ctx.delegatedStakesWithdrawals.map(_.size).getOrElse(0)}"
      parts += s"nodeCollaterals=${ctx.activeNodeCollaterals.map(_.size).getOrElse(0)}"
      parts += s"collateralWithdrawals=${ctx.nodeCollateralWithdrawals.map(_.size).getOrElse(0)}"
      parts += s"updateNodeParams=${ctx.updateNodeParameters.map(_.size).getOrElse(0)}"
      parts += s"priceState=${ctx.priceState.map(_.size).getOrElse(0)}"
      parts += s"metagraphSync=${ctx.metagraphSyncData.map(_.size).getOrElse(0)}"
      parts.result().mkString(" ")
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
        // Round succeeded — discard the proposal savepoint so it won't be restored on the next ordinal
        _ <- proposalSavepointRef.set(none)
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

    /** Advances from Signatures to Finished once quorum valid signatures are collected.
      *
      * Collects signature declarations, verifies each against the artifact hash, and transitions to Finished with the signed artifact. Uses
      * the artifact hash (not signed-artifact hash) as `snapshotHash` to avoid non-determinism from varying signature counts across peers.
      */
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
            // Skip facilitatorsHash fork check when view > 0 (eviction happened)
            _ <- maybeSignatures
              .traverse_(checkForkByFacilitatorsHash(_, status.facilitatorsHash)(_.facilitatorsHash))
              .whenA(state.viewNumber === 0)
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
        role = ConsensusLog.role(selfId, state.leader)
        _ <- ConsensusLog.info(
          logger,
          ConsensusLog.Phase,
          state.key.show,
          role,
          "event" -> "SIGNATURES_TO_FINISHED",
          "ordinal" -> status.majorityArtifactInfo.artifact.ordinal.show,
          "signatures" -> s"${valid.size}/${proofs.size}",
          "hash" -> status.majorityArtifactInfo.hash.show.take(8),
          "trigger" -> status.majorityTrigger.toString,
          "leader" -> ConsensusLog.pid(state.leader),
          "self" -> ConsensusLog.pid(selfId),
          "view" -> state.viewNumber.toString
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
        ConsensusLog.error(logger, ConsensusLog.Lifecycle, signedArtifact.ordinal.show, "n/a", "event" -> "PERSIST_FAILED") >> MonadThrow[F]
          .raiseError(
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
