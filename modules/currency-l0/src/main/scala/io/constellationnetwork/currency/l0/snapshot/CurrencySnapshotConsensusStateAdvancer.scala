package io.constellationnetwork.currency.l0.snapshot

import java.security.KeyPair

import cats.Applicative
import cats.data.{NonEmptySet, StateT}
import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.SortedMap
import scala.concurrent.duration.FiniteDuration

import io.constellationnetwork.currency.dataApplication.BaseDataApplicationL0Service
import io.constellationnetwork.currency.l0.snapshot.schema._
import io.constellationnetwork.currency.l0.snapshot.services.StateChannelSnapshotService
import io.constellationnetwork.currency.schema.CurrencyStateKey
import io.constellationnetwork.currency.schema.currency.CurrencySnapshotContext
import io.constellationnetwork.ext.collection.FoldableOps.pickMajority
import io.constellationnetwork.ext.crypto._
import io.constellationnetwork.node.shared.config.types.ConsensusConfig
import io.constellationnetwork.node.shared.domain.cluster.storage.ClusterStorage
import io.constellationnetwork.node.shared.domain.consensus.ConsensusFunctions.InvalidArtifact
import io.constellationnetwork.node.shared.domain.gossip.Gossip
import io.constellationnetwork.node.shared.domain.node.NodeStorage
import io.constellationnetwork.node.shared.infrastructure.consensus.ConsensusLog.{Category, Event}
import io.constellationnetwork.node.shared.infrastructure.consensus._
import io.constellationnetwork.node.shared.infrastructure.consensus.declaration._
import io.constellationnetwork.node.shared.infrastructure.consensus.message._
import io.constellationnetwork.node.shared.infrastructure.consensus.state.ConsensusStateUpdater._
import io.constellationnetwork.node.shared.infrastructure.consensus.state._
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.{ConsensusTrigger, EventTrigger, TimeTrigger}
import io.constellationnetwork.node.shared.infrastructure.gossip.event.{EventGossipClient, IWantRequest}
import io.constellationnetwork.node.shared.infrastructure.mempool.EventMempool
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.node.shared.infrastructure.node.RestartService
import io.constellationnetwork.node.shared.infrastructure.snapshot.SnapshotConsensusFunctions.gossipForkInfo
import io.constellationnetwork.node.shared.infrastructure.snapshot.{
  CurrencyArtifactMismatch,
  SnapshotDifferentThanExpected,
  SomeBlocksWereNotAccepted
}
import io.constellationnetwork.node.shared.snapshot.currency._
import io.constellationnetwork.schema.currencyMessage.fetchStakingAddress
import io.constellationnetwork.schema.peer.{Peer, PeerId}
import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, SnapshotOrdinal}
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.signature._
import io.constellationnetwork.statechannel.StateChannelSnapshotBinary
import io.constellationnetwork.syntax.sortedCollection.sortedSetSyntax

import eu.timepit.refined.auto._
import io.circe.{Decoder, Encoder}
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

/** Advances Currency L0 (Metagraph) consensus through status phases and extracts final outcomes.
  *
  * Status Flow (note: has extra BinarySignatures phase compared to Global L0):
  * {{{
  *   CollectingFacilities → CollectingProposals → CollectingSignatures
  *     → CollectingBinarySignatures → Finished
  * }}}
  *
  * @see
  *   ConsensusStateAdvancer for the generic interface
  */
abstract class CurrencySnapshotConsensusStateAdvancer[F[_]]
    extends ConsensusStateAdvancer[
      F,
      CurrencySnapshotKey,
      CurrencySnapshotArtifact,
      CurrencySnapshotContext,
      CurrencySnapshotStatus,
      CurrencyConsensusOutcome,
      CurrencyConsensusKind
    ]

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
    clusterStorageInstance: ClusterStorage[F],
    eventMempool: EventMempool[F, CurrencySnapshotEvent, CurrencyStateKey],
    eventGossipClient: EventGossipClient[F, CurrencySnapshotEvent]
  )(
    implicit eventEncoder: Encoder[CurrencySnapshotEvent],
    eventDecoder: Decoder[CurrencySnapshotEvent]
  ): CurrencySnapshotConsensusStateAdvancer[F] =
    new CurrencySnapshotConsensusStateAdvancer[F] {

      private val logger: SelfAwareStructuredLogger[F] = Slf4jLogger.getLoggerFromClass[F](getClass)
      private val lastSnapshotHashObservationName = "last-snapshot-hash"
      private val facilitatorsHashObservationName = "facilitators-hash"
      private val consensusConfigHashObservationName = "consensus-config-hash"

      protected val clusterStorage: ClusterStorage[F] = clusterStorageInstance
      protected val config: ConsensusConfig = consensusConfig

      private case class Transition(newState: CurrencySnapshotConsensusState, sideEffect: F[Unit])

      override def isBootstrapActive(lastOutcome: CurrencyConsensusOutcome): Boolean =
        !lastOutcome.recentProofSizes.values.exists(_ >= config.bootstrapCompleteProofsThreshold)

      def getConsensusOutcome(
        state: CurrencySnapshotConsensusState
      ): Option[(Previous[CurrencySnapshotKey], CurrencyConsensusOutcome)] =
        state.status match {
          case f: Finished =>
            // Phase 3: derive penalty/quality state from CONSENSUS-AGREED inputs only.
            // See GlobalSnapshotConsensusStateAdvancer for the full rationale — summary:
            // `f.signedMajorityArtifact.proofs` varies across nodes for the same artifact
            // (maybeGetAllDeclarations stops at quorum; SnapshotStorage.prepend doesn't
            // merge later-arriving proofs; ForkInfo gossip carries only (ordinal, hash)).
            // Derive penalties, quality, and bootstrap classification from
            // `state.facilitators` / `state.removedFacilitators` only.
            val evictedPeers = state.removedFacilitators.value
            val previousPenalties = state.lastOutcome.removalPenalties
            val previousCumulative = state.lastOutcome.cumulativeMissCounts

            val deferredInCommittee = state.lastOutcome.deferralCountdown.filter(_._2 > 0).keySet

            val completedFacilitators = state.facilitators.value.toSet -- evictedPeers
            val decayedCumulative = completedFacilitators.foldLeft(previousCumulative) { (acc, pid) =>
              acc.get(pid) match {
                case Some(v) if v > 1L => acc.updated(pid, v - 1L)
                case Some(_)           => acc - pid // reached 0 — prune so the map stays bounded
                case None              => acc // no prior miss history, nothing to decay
              }
            }

            // Bootstrap warmup: classify from consensus-agreed committee size rather than
            // locally-observed proofs count.
            val isInBootstrap =
              !state.lastOutcome.recentProofSizes.values.exists(_ >= config.bootstrapCompleteProofsThreshold)

            val penalizedThisRound =
              if (isInBootstrap) Set.empty[PeerId] else (evictedPeers -- deferredInCommittee).toSet
            val newCumulative = penalizedThisRound.foldLeft(decayedCumulative) { (acc, pid) =>
              acc.updated(pid, acc.getOrElse(pid, 0L) + 1L)
            }

            val decrementedPenalties = previousPenalties.view.mapValues(_ - 1).filter(_._2 > 0).to(SortedMap)
            val newPenalties = penalizedThisRound.foldLeft(decrementedPenalties) { (acc, pid) =>
              val repeatCount = newCumulative.getOrElse(pid, 1L) - 1L
              val base = config.exponentialPenaltyBase.toDouble
              val scaled = config.removalPenaltyRounds.toDouble * math.pow(base, repeatCount.toDouble)
              val penalty = math.min(scaled, config.maxRemovalPenaltyRounds.toDouble).toInt
              acc.updated(pid, math.max(1, penalty))
            }
            // Compute deferral countdown: same pattern as removal penalties.
            val previousEligibleSet = state.lastOutcome.eligibleOrFacilitators.toSet
            val currentEligibleSet = state.eligibleFacilitators.value.toSet
            val newlyEligible = (currentEligibleSet -- previousEligibleSet).filterNot(completedFacilitators.contains)
            val justUnpenalized = previousPenalties.filter(_._2 == 1).keySet
            val needsDeferral = newlyEligible ++ justUnpenalized
            val previousDeferrals = state.lastOutcome.deferralCountdown
            val decrementedDeferrals = previousDeferrals.view.mapValues(_ - 1).filter(_._2 > 0).to(SortedMap)
            val newDeferrals = needsDeferral.foldLeft(decrementedDeferrals) { (acc, pid) =>
              if (!acc.contains(pid)) acc.updated(pid, config.candidateDeferralRounds)
              else acc
            }
            val finalDeferrals = if (config.candidateDeferralRounds > 0) newDeferrals else SortedMap.empty[PeerId, Int]

            val thisRoundQuality: SortedMap[PeerId, (Int, Int)] = SortedMap.from(
              state.facilitators.value.map { pid =>
                val completed = if (completedFacilitators.contains(pid)) 1 else 0
                pid -> (completed, 1)
              }
            )
            // Accumulate with previous rounds, apply deterministic decay and pruning
            val rawAccumulated: SortedMap[PeerId, (Int, Int)] = {
              val previous = state.lastOutcome.peerQuality
              val allPeerIds = (previous.keySet.toList ::: thisRoundQuality.keySet.toList).distinct
              SortedMap.from(allPeerIds.map { pid =>
                val (pc, pp) = previous.getOrElse(pid, (0, 0))
                val (tc, tp) = thisRoundQuality.getOrElse(pid, (0, 0))
                pid -> (pc + tc, pp + tp)
              })
            }
            val needsDecay = rawAccumulated.values.exists { case (_, p) => p > consensusConfig.qualityDecayThreshold }
            val decayed =
              if (needsDecay) rawAccumulated.view.mapValues { case (c, p) => (c / 2, p / 2) }.to(SortedMap)
              else rawAccumulated
            val accumulatedQuality = decayed.filter { case (_, (c, p)) => c > 0 || p > 0 }

            // Roll the proofs-size window forward using consensus-agreed committee size.
            val bootstrapLookbackOrdinals = 10L
            val currentOrdValue = state.key.value.value
            val minOrdinalValue = math.max(0L, currentOrdValue - bootstrapLookbackOrdinals)
            val currentProofsSize: Int = completedFacilitators.size
            val newRecentProofSizes: SortedMap[SnapshotOrdinal, Int] = {
              val withCurrent =
                state.lastOutcome.recentProofSizes.updated(state.key, currentProofsSize)
              withCurrent.filter { case (ord, _) => ord.value.value >= minOrdinalValue }
            }

            val outcome = CurrencyConsensusOutcome(
              state.key,
              state.facilitators,
              state.removedFacilitators,
              state.withdrawnFacilitators,
              state.eligibleFacilitators,
              f,
              removalPenalties = if (config.removalPenaltyRounds > 0) newPenalties else SortedMap.empty,
              deferralCountdown = finalDeferrals,
              peerQuality = accumulatedQuality,
              cumulativeMissCounts = newCumulative,
              recentProofSizes = newRecentProofSizes
            )
            (Previous(state.lastOutcome.key), outcome).some
          case _ =>
            none
        }

      def advanceStatus(
        resources: ConsensusResources[CurrencySnapshotArtifact, CurrencyConsensusKind]
      ): StateT[F, CurrencySnapshotConsensusState, F[Unit]] =
        StateT { state =>
          HasherSelector[F].withCurrent { implicit hasher =>
            tryAdvance(state, resources).map {
              case Some(t) => (t.newState, t.sideEffect)
              case None    => (state, Applicative[F].unit)
            }
          }
        }

      private def tryAdvance(
        state: CurrencySnapshotConsensusState,
        resources: ConsensusResources[CurrencySnapshotArtifact, CurrencyConsensusKind]
      )(implicit hasher: Hasher[F]): F[Option[Transition]] =
        state.status match {
          case s: CollectingFacilities       => advanceFromFacilities(state, s, resources)
          case s: CollectingProposals        => advanceFromProposals(state, s, resources)
          case s: CollectingSignatures       => advanceFromSignatures(state, s, resources)
          case s: CollectingBinarySignatures => advanceFromBinarySignatures(state, s, resources)
          case _: Finished                   => none[Transition].pure[F]
        }

      // =========================================================================
      // COLLECTING FACILITIES → COLLECTING PROPOSALS
      // =========================================================================

      private def advanceFromFacilities(
        state: CurrencySnapshotConsensusState,
        status: CollectingFacilities,
        resources: ConsensusResources[CurrencySnapshotArtifact, CurrencyConsensusKind]
      ): F[Option[Transition]] =
        for {
          maybeFacilities <- maybeGetAllDeclarations(state, resources)(_.facility)
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
                  Category.Fork,
                  state.key.show,
                  "n/a",
                  Event.ForkedPeersEvicted,
                  "evicted" -> evicted.size.toString,
                  "remaining" -> clean.size.toString,
                  "evictedPeers" -> evicted.toList.map(ConsensusLog.pid).mkString(",")
                )
                .whenA(evicted.nonEmpty)
          }

          result <- cleanFacilities.flatTraverse { facilities =>
            // Only fork-evicted peers (divergent facilitatorsHash) accumulate into
            // state.removedFacilitators — that set is consensus-agreed. Missing-declaration
            // peers remain in state.facilitators (they just don't participate this round);
            // evicting them would depend on local gossip-arrival timing and would diverge
            // across nodes. See dag-l0 mirror for full rationale.
            val forkEvictedPeers: Set[PeerId] = maybeFacilities match {
              case Some(orig) => orig.keySet -- facilities.keySet
              case None       => Set.empty
            }
            val updatedState: CurrencySnapshotConsensusState =
              if (forkEvictedPeers.nonEmpty)
                state.copy[CurrencySnapshotKey, CurrencySnapshotStatus, CurrencyConsensusOutcome, CurrencyConsensusKind](
                  facilitators = Facilitators(state.facilitators.value.filter(pid => !forkEvictedPeers.contains(pid))),
                  removedFacilitators = RemovedFacilitators(state.removedFacilitators.value ++ forkEvictedPeers)
                )
              else state
            toProposalsPhase(updatedState, facilities)
          }
        } yield result

      private def toProposalsPhase(
        state: CurrencySnapshotConsensusState,
        facilities: SortedMap[PeerId, Facility]
      ): F[Option[Transition]] = {
        val (candidates, triggers) = facilities.foldMap(f => (f.candidates.value, f.trigger.toList))

        // Compute hash UNION - include events ANY facilitator has, then sync missing
        val allHashSets = facilities.values.map(_.eventHashes).toList
        val unionHashes = allHashSets.reduceOption(_ union _).getOrElse(Set.empty[Hash])

        val trigger = pickMajority(triggers).getOrElse(EventTrigger)

        // Build map of hash -> ALL peers who have it (for resilient fetching).
        // Previously used toMap which kept only the last peer per hash — if that peer was
        // unavailable the event was silently dropped. Now we retain all candidates and try
        // them in order until one succeeds.
        val hashToPeers: Map[Hash, List[PeerId]] = facilities.toList.flatMap {
          case (peerId, facility) => facility.eventHashes.map(_ -> peerId)
        }
          .groupMap(_._1)(_._2)

        for {
          // Get local hashes and identify what we're missing
          localHashes <- eventMempool.getEventHashes
          missingHashes = unionHashes -- localHashes

          // Sync missing events from peers before building proposal
          _ <- syncMissingEvents(missingHashes, hashToPeers).whenA(missingHashes.nonEmpty)

          result <- buildProposalTransition(state, unionHashes, candidates, trigger)
        } yield result
      }

      /** Sync missing events from peers who have them.
        *
        * For each missing hash we try each candidate peer in order, stopping as soon as one succeeds. This prevents a single unavailable
        * peer from causing an event to be silently skipped for the round.
        */
      // TODO: Group by first-available peer and fetch in parallel (parTraverse) like dag-l0 does.
      // Currently serializes per-hash requests; under load, adds latency proportional to missingHashes.size × RTT.
      private def syncMissingEvents(
        missingHashes: Set[Hash],
        hashToPeers: Map[Hash, List[PeerId]]
      ): F[Unit] =
        missingHashes.toList.traverse_ { hash =>
          hashToPeers.getOrElse(hash, Nil) match {
            case Nil   => Async[F].unit
            case peers =>
              // foldLeftM short-circuits on first success: once a peer returns the event, remaining peers are skipped
              peers
                .foldLeftM(false) { (found, peerId) =>
                  if (found) true.pure[F]
                  else fetchEventFromPeer(peerId, hash)
                }
                .flatMap { fetched =>
                  if (fetched) Async[F].unit
                  else logger.warn(s"[EventSync] Could not fetch hash ${hash.show.take(8)} from any of ${peers.size} peers")
                }
          }
        }

      /** Attempt to fetch a single event hash from a peer. Returns true if the event was successfully added to the mempool. */
      private def fetchEventFromPeer(peerId: PeerId, hash: Hash): F[Boolean] =
        clusterStorage.getPeer(peerId).flatMap {
          case None => false.pure[F]
          case Some(peer) =>
            eventGossipClient
              .requestEvents(IWantRequest(Set(hash)))
              .run(Peer.toP2PContext(peer))
              .flatMap { response =>
                response.events.traverse_ {
                  case (_, signedEvent) => eventMempool.add(signedEvent).void
                }.as(response.events.exists(_._1 === hash))
              }
              .handleErrorWith { err =>
                logger
                  .warn(s"[EventSync] Failed to fetch ${hash.show.take(8)} from peer ${peerId.show.take(8)}: ${err.getMessage}")
                  .as(false)
              }
        }

      private def buildProposalTransition(
        state: CurrencySnapshotConsensusState,
        commonHashes: Set[Hash],
        candidates: Set[PeerId],
        majorityTrigger: ConsensusTrigger
      ): F[Option[Transition]] =
        HasherSelector[F].withCurrent { implicit hasher =>
          for {
            _ <- clearTimeTriggerIfNeeded(majorityTrigger)
            facilitatorsHash <- hashFacilitators(state)

            // Pull events from mempool using hash union across all facilitator declarations
            mempoolData <- eventMempool.getMultiple(commonHashes).map { hashToHashed =>
              val events = hashToHashed.values.map(_.signed.value).toSet
              val hashToEvent = hashToHashed.map { case (h, hashed) => h -> hashed.signed.value }
              (events, hashToEvent)
            }
            (mempoolEvents, mempoolHashToEvent) = mempoolData

            (artifact, context, returnedEvents) <- createArtifact(state, majorityTrigger, mempoolEvents)

            // Clear included events from mempool (events not returned were included)
            includedHashes = {
              val returnedSet = returnedEvents.toSet
              mempoolHashToEvent.collect {
                case (hash, event) if !returnedSet.contains(event) => hash
              }.toSet
            }
            _ <- eventMempool.clearIncluded(includedHashes)

            hash <- hashArtifact(artifact)
            isLeader = selfId === state.leader
            role = if (isLeader) "LEADER" else "FOLLOWER"
            withdrawnCount = state.withdrawnFacilitators.value.size
            _ <- logger.info(
              s"[CONSENSUS:$role] FACILITIES->PROPOSALS key=${state.key.show} ordinal=${artifact.ordinal.show} trigger=$majorityTrigger " +
                s"hash=${hash.show.take(8)}... facilitators=${state.facilitators.value.size} candidates=${candidates.size} " +
                s"leader=${state.leader.show.take(8)}... self=${selfId.show.take(8)}... view=${state.viewNumber}" +
                (if (withdrawnCount > 0) s" withdrawn=$withdrawnCount" else "") +
                s" facilitatorsHash=${facilitatorsHash.show.take(8)}... lastSnapshotHash=${state.lastOutcome.finished.snapshotHash.show
                    .take(8)}... entropy=${state.entropy.show.take(8)}..."
            )

            leaderLock <- consensusStorage.getVoteLock(state.key)
            maybeAssembledVcc <-
              if (state.viewNumber > 0) consensusStorage.getAssembledVcc(state.key) else none[ViewChangeCertificate].pure[F]
            vccHighestQc = maybeAssembledVcc.flatMap(_.highestQcInVcc)
            vccMismatch = isLeader && state.viewNumber > 0 && vccHighestQc.exists(_.proposalHash =!= hash)
            vccMissing = isLeader && state.viewNumber > 0 && maybeAssembledVcc.isEmpty
            aborted = (isLeader && leaderLock.flatMap(_.lockedQc).exists(_.proposalHash =!= hash)) || vccMismatch || vccMissing
            _ <- logger
              .warn(
                s"[CONSENSUS:$role] Leader locked on different QC key=${state.key.show} lockedQcHash=${leaderLock
                    .flatMap(_.lockedQc)
                    .map(_.proposalHash.show.take(8))
                    .getOrElse("none")} proposingHash=${hash.show.take(8)}"
              )
              .whenA(isLeader && leaderLock.flatMap(_.lockedQc).exists(_.proposalHash =!= hash))
            _ <- logger
              .warn(
                s"[CONSENSUS:$role] Leader VCC highest-QC mismatch key=${state.key.show} view=${state.viewNumber} " +
                  s"qcHash=${vccHighestQc.map(_.proposalHash.show.take(8)).getOrElse("none")} " +
                  s"proposingHash=${hash.show.take(8)}"
              )
              .whenA(vccMismatch)
            _ <- logger
              .warn(s"[CONSENSUS:$role] Leader VCC missing for view>0 key=${state.key.show} view=${state.viewNumber}")
              .whenA(vccMissing)
          } yield
            if (aborted) none[Transition]
            else
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
                    spreadProposal(
                      state,
                      state.key,
                      hash,
                      facilitatorsHash,
                      artifact,
                      state.lastOutcome.finished.snapshotHash,
                      state.viewNumber.toLong,
                      maybeAssembledVcc
                    )
                  else
                    Applicative[F].unit
              ).some
        }

      // =========================================================================
      // COLLECTING PROPOSALS → COLLECTING SIGNATURES
      // =========================================================================

      // ---- Leader-based proposal resolution ----
      // Only the leader spreads a Proposal + ConsensusArtifact. Non-leaders wait for the leader's
      // proposal to arrive via gossip, then validate the leader's artifact to obtain the context
      // needed for signing. If the leader's hash matches our own artifact, we use our local
      // ArtifactInfo directly (no extra validation needed).

      private def advanceFromProposals(
        state: CurrencySnapshotConsensusState,
        status: CollectingProposals,
        resources: ConsensusResources[CurrencySnapshotArtifact, CurrencyConsensusKind]
      )(implicit hasher: Hasher[F]): F[Option[Transition]] = {
        // Guard: if we already withdrew from this round, don't re-enter validation.
        // Without this, a validation failure (which returns none[Transition]) causes a hot loop:
        // the leader's proposal stays in resources, so every checkUpdate re-enters here,
        // re-validates, re-fails, and re-withdraws (7+/sec observed in production).
        val alreadyWithdrawn =
          resources.withdrawalsMap.get(selfId).contains(CurrencyConsensusKind.Signature: CurrencyConsensusKind) ||
            state.withdrawnFacilitators.value.contains(selfId)

        if (alreadyWithdrawn)
          none[Transition].pure[F]
        else {
          val leader = state.leader
          val maybeLeaderProposal = resources.peerDeclarationsMap.get(leader).flatMap(_.proposal)

          maybeLeaderProposal match {
            case Some(leaderProposal) =>
              for {
                // Skip facilitatorsHash fork check when view > 0 (eviction), solo→multi transition,
                // or during joining grace period (peer quality scores haven't converged yet).
                lastSolo <- wasLastRoundSolo
                inGrace <- nodeStorage.isInJoiningGracePeriod
                _ <- checkForkByFacilitatorsHash(
                  SortedMap(leader -> leaderProposal),
                  status.facilitatorsHash
                )(_.facilitatorsHash).whenA(!lastSolo && !inGrace)
                _ <- checkForkByLastSnapshotHash(
                  SortedMap(leader -> leaderProposal),
                  status.lastSnapshotHash
                )
                result <- resolveLeaderProposal(state, status, resources, leaderProposal)
              } yield result
            case None =>
              if (selfId === state.leader)
                // Leader (possibly after view change) — spread proposal so peers can advance.
                // Include any assembled VCC for view > 0 so followers accept the re-spread.
                (if (state.viewNumber > 0) consensusStorage.getAssembledVcc(state.key) else none[ViewChangeCertificate].pure[F]).flatMap {
                  maybeVcc =>
                    logger.info(
                      s"[CONSENSUS:LEADER] Re-spreading proposal key=${state.key.show} hash=${status.proposalArtifactInfo.hash.show.take(8)}... " +
                        s"targets=${state.facilitators.value.size} view=${state.viewNumber}"
                    ) >>
                      spreadProposal(
                        state,
                        state.key,
                        status.proposalArtifactInfo.hash,
                        status.facilitatorsHash,
                        status.proposalArtifactInfo.artifact,
                        status.lastSnapshotHash,
                        state.viewNumber.toLong,
                        maybeVcc
                      ).as(none[Transition])
                }
              else
                none[Transition].pure[F]
          }
        }
      }

      /** Validate view/VCC invariants on an incoming proposal. Mirrors GlobalSnapshotConsensusStateAdvancer.validateProposalVcc. */
      private def validateProposalVcc(
        state: CurrencySnapshotConsensusState,
        proposal: Proposal,
        facilitatorsHash: Hash
      ): Either[String, Unit] = {
        val n = state.facilitators.value.size
        val q = math.max(1, math.ceil(n.toDouble * config.quorumThresholdFraction).toInt)
        if (proposal.view === 0L) {
          if (proposal.vcc.nonEmpty) Left("view0_proposal_must_not_carry_vcc")
          else Right(())
        } else {
          proposal.vcc match {
            case None => Left(s"view${proposal.view}_proposal_missing_vcc")
            case Some(vcc) if vcc.votes.size < q =>
              Left(s"vcc_under_quorum votes=${vcc.votes.size} required=$q")
            case Some(vcc) if vcc.facilitatorsHash =!= facilitatorsHash =>
              Left(
                s"vcc_facilitators_mismatch vccFacHash=${vcc.facilitatorsHash.show.take(8)} ours=${facilitatorsHash.show.take(8)}"
              )
            case Some(vcc) =>
              vcc.highestQcInVcc match {
                case Some(qc) if qc.proposalHash =!= proposal.hash =>
                  Left(
                    s"highest_qc_carry_forward_violation qcHash=${qc.proposalHash.show.take(8)} proposalHash=${proposal.hash.show.take(8)}"
                  )
                case _ => Right(())
              }
          }
        }
      }

      /** Verify cryptographic signatures on every `Signed[ViewChangeVote]` inside the VCC. Mirrors the dag-l0 helper. */
      private def verifyVccSignatures(vcc: ViewChangeCertificate)(implicit hasher: Hasher[F]): F[Either[String, Unit]] =
        vcc.votes.toNonEmptyList.traverse { signedVote =>
          signedVote.hasValidSignature[F].map {
            case true  => Right(()): Either[String, Unit]
            case false => Left(signedVote.proofs.head.id.show.take(8))
          }
        }.map { results =>
          val invalidPeers = results.toList.collect { case Left(pid) => pid }
          if (invalidPeers.isEmpty) Right(())
          else Left(s"vcc_invalid_signatures peers=${invalidPeers.mkString(",")}")
        }

      private def resolveLeaderProposal(
        state: CurrencySnapshotConsensusState,
        status: CollectingProposals,
        resources: ConsensusResources[CurrencySnapshotArtifact, CurrencyConsensusKind],
        leaderProposal: Proposal
      )(implicit hasher: Hasher[F]): F[Option[Transition]] = {
        def logVccReject(reason: String): F[Option[Transition]] =
          logger
            .warn(s"[CONSENSUS] VCC validation failed key=${state.key.show} view=${state.viewNumber} reason=$reason")
            .as(none[Transition])
        validateProposalVcc(state, leaderProposal, status.facilitatorsHash) match {
          case Left(reason) => logVccReject(reason)
          case Right(_) =>
            leaderProposal.vcc match {
              case Some(vcc) =>
                verifyVccSignatures(vcc).flatMap {
                  case Left(reason) => logVccReject(reason)
                  case Right(_)     => resolveLeaderProposalInner(state, status, resources, leaderProposal)
                }
              case None => resolveLeaderProposalInner(state, status, resources, leaderProposal)
            }
        }
      }

      private def resolveLeaderProposalInner(
        state: CurrencySnapshotConsensusState,
        status: CollectingProposals,
        resources: ConsensusResources[CurrencySnapshotArtifact, CurrencyConsensusKind],
        leaderProposal: Proposal
      )(implicit hasher: Hasher[F]): F[Option[Transition]] = {
        val role = if (selfId === state.leader) "LEADER" else "FOLLOWER"
        if (leaderProposal.hash === status.proposalArtifactInfo.hash) {
          // Leader's artifact matches our own — use local ArtifactInfo (avoids re-validation)
          logger.info(
            s"[CONSENSUS:$role] PROPOSALS->SIGNATURES key=${state.key.show} matchesOwn=true hash=${leaderProposal.hash.show.take(8)}... " +
              s"trigger=${status.majorityTrigger} leader=${state.leader.show.take(8)}... self=${selfId.show.take(8)}... view=${state.viewNumber}"
          ) >>
            Metrics[F].incrementCounter("dag_consensus_proposal_affinity_match") >>
            buildSignatureTransition(state, status, status.proposalArtifactInfo, List(leaderProposal.hash), leaderProposal.vcc)
        } else {
          // Leader proposed a different artifact — validate theirs
          resources.artifacts.get(leaderProposal.hash) match {
            case Some(leaderArtifact) =>
              validateLeaderArtifact(state, status, leaderArtifact, leaderProposal.hash).flatMap {
                case Right(leaderInfo) =>
                  logger.info(
                    s"[CONSENSUS:$role] PROPOSALS->SIGNATURES key=${state.key.show} matchesOwn=false " +
                      s"leaderHash=${leaderProposal.hash.show.take(8)}... ownHash=${status.proposalArtifactInfo.hash.show.take(8)}... " +
                      s"trigger=${status.majorityTrigger} leader=${state.leader.show.take(8)}... self=${selfId.show.take(8)}... view=${state.viewNumber}"
                  ) >>
                    Metrics[F].incrementCounter("dag_consensus_proposal_affinity_mismatch_accepted") >>
                    buildSignatureTransition(state, status, leaderInfo, List(leaderProposal.hash), leaderProposal.vcc)
                case Left(invalidArtifact) =>
                  val diffDetail = describeInvalidArtifact(invalidArtifact)
                  logger.warn(
                    s"[CONSENSUS:$role] Leader proposal FAILED validation key=${state.key.show} " +
                      s"leaderHash=${leaderProposal.hash.show.take(8)}... ownHash=${status.proposalArtifactInfo.hash.show.take(8)}... " +
                      s"leader=${state.leader.show.take(8)}... view=${state.viewNumber} reason=$diffDetail"
                  ) >>
                    logger.info(
                      s"[CONSENSUS:$role] Withdrawing from round key=${state.key.show} reason=proposal_validation_failed"
                    ) >>
                    gossip.spread(ConsensusWithdrawPeerDeclaration(state.key, CurrencyConsensusKind.Signature: CurrencyConsensusKind)) >>
                    Metrics[F].incrementCounter("dag_consensus_proposal_validation_failure") >>
                    Metrics[F].incrementCounter("dag_consensus_withdrawal_sent") >>
                    none[Transition].pure[F]
              }
            case None =>
              // Leader's artifact not yet received via gossip — wait
              none[Transition].pure[F]
          }
        }
      }

      private def validateLeaderArtifact(
        state: CurrencySnapshotConsensusState,
        status: CollectingProposals,
        artifact: CurrencySnapshotArtifact,
        hash: Hash
      )(implicit hasher: Hasher[F]): F[Either[InvalidArtifact, ArtifactInfo[CurrencySnapshotArtifact, CurrencySnapshotContext]]] =
        consensusFns
          .validateArtifact(
            state.lastOutcome.finished.signedMajorityArtifact,
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
              err.asLeft[ArtifactInfo[CurrencySnapshotArtifact, CurrencySnapshotContext]]
          }

      /** Produces a human-readable description of why the leader's artifact failed validation. */
      private def describeInvalidArtifact(err: InvalidArtifact): String = err match {
        case CurrencyArtifactMismatch(errors) =>
          val descriptions = errors.map {
            case SnapshotDifferentThanExpected(expected, actual) =>
              val diffs = List.newBuilder[String]
              if (expected.ordinal =!= actual.ordinal) diffs += s"ordinal(leader=${expected.ordinal.show},own=${actual.ordinal.show})"
              if (expected.height =!= actual.height) diffs += s"height(leader=${expected.height.show},own=${actual.height.show})"
              if (expected.blocks.size != actual.blocks.size) diffs += s"blocks(leader=${expected.blocks.size},own=${actual.blocks.size})"
              if (expected.rewards.size != actual.rewards.size)
                diffs += s"rewards(leader=${expected.rewards.size},own=${actual.rewards.size})"
              if (expected.lastSnapshotHash =!= actual.lastSnapshotHash)
                diffs += s"lastSnapshotHash(leader=${expected.lastSnapshotHash.show.take(8)},own=${actual.lastSnapshotHash.show.take(8)})"
              if (expected.tips =!= actual.tips) diffs += "tipsDiffer"
              if (expected.stateProof =!= actual.stateProof) diffs += "stateProofDiffers"
              val result = diffs.result()
              if (result.isEmpty) "SnapshotDifferentThanExpected(no field-level diff — possible serialization difference)"
              else s"SnapshotDifferentThanExpected[${result.mkString(",")}]"
            case SomeBlocksWereNotAccepted(awaiting, rejected) =>
              s"SomeBlocksWereNotAccepted(awaiting=${awaiting.size},rejected=${rejected.size})"
            case other =>
              other.show
          }
          s"CurrencyArtifactMismatch[${descriptions.mkString(";")}]"
        case other =>
          other.getClass.getSimpleName
      }

      private def buildSignatureTransition(
        state: CurrencySnapshotConsensusState,
        status: CollectingProposals,
        majorityInfo: ArtifactInfo[CurrencySnapshotArtifact, CurrencySnapshotContext],
        proposalHashes: List[Hash],
        leaderVcc: Option[ViewChangeCertificate] = None
      )(implicit hasher: Hasher[F]): F[Option[Transition]] =
        for {
          facilitatorsHash <- state.facilitators.value.hash
          view = state.viewNumber.toLong
          localLock <- consensusStorage.getVoteLock(state.key)
          effectiveLockedQc = VoteLock.maxByView(
            localLock.flatMap(_.lockedQc),
            leaderVcc.flatMap(_.highestQcInVcc)
          )
          tryLock <- consensusStorage.tryLockVote(state.key, view, majorityInfo.hash, effectiveLockedQc)
          result <- tryLock match {
            case Left(reason) =>
              logger
                .warn(
                  s"[CONSENSUS] Vote lock rejected key=${state.key.show} view=$view hash=${majorityInfo.hash.show.take(8)} reason=$reason"
                )
                .as(none[Transition])
            case Right(_) =>
              for {
                // Sign the proposal artifact hash directly. See dag-l0 mirror for rationale: widening the
                // signing domain would break Signed[artifact] verification in toFinishedPhase. Safety against
                // double-signing is enforced at the VoteLock gate above.
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
                  sideEffect = spreadSignature(
                    state,
                    state.key,
                    signature,
                    facilitatorsHash,
                    state.lastOutcome.finished.snapshotHash,
                    view,
                    majorityInfo.hash
                  )
                ).some
          }
        } yield result

      /** Canonical byte encoding of the signing domain for MajoritySignature: `(key, view, proposalHash, facilitatorsHash)`. */
      private def canonicalSignBytes(
        key: CurrencySnapshotKey,
        view: Long,
        proposalHash: Hash,
        facilitatorsHash: Hash
      ): Array[Byte] = {
        val sb = new StringBuilder(128)
        sb.append("MS|")
          .append(key.show)
          .append('|')
          .append(view)
          .append('|')
          .append(proposalHash.value)
          .append('|')
          .append(facilitatorsHash.value)
        sb.toString.getBytes("UTF-8")
      }

      // =========================================================================
      // COLLECTING SIGNATURES → COLLECTING BINARY SIGNATURES
      // =========================================================================

      private def advanceFromSignatures(
        state: CurrencySnapshotConsensusState,
        status: CollectingSignatures,
        resources: ConsensusResources[CurrencySnapshotArtifact, CurrencyConsensusKind]
      ): F[Option[Transition]] =
        for {
          maybeSignatures <- maybeGetAllDeclarations(state, resources)(_.signature)
          maybeFacilities <- maybeGetAllDeclarations(state, resources)(_.facility)
          // Skip facilitatorsHash fork check when view > 0 (eviction), solo→multi transition,
          // or during joining grace period (peer quality scores haven't converged yet).
          lastSolo2 <- wasLastRoundSolo
          inGrace2 <- nodeStorage.isInJoiningGracePeriod
          _ <- maybeSignatures
            .traverse_(checkForkByFacilitatorsHash(_, status.facilitatorsHash)(_.facilitatorsHash))
            .whenA(!lastSolo2 && !inGrace2)
          _ <- maybeSignatures.traverse_(checkForkByLastSnapshotHash(_, status.lastSnapshotHash))
          maybeGlobalOrd = extractGlobalSnapshotOrdinal(maybeFacilities)
          result <- (maybeGlobalOrd, maybeSignatures) match {
            case (Some(globalOrd), Some(signatures)) =>
              HasherSelector[F].withCurrent { implicit hs =>
                toBinarySignaturesPhase(state, status, globalOrd, signatures)
              }
            case _ =>
              none[Transition].pure[F]
          }
        } yield result

      private def extractGlobalSnapshotOrdinal(maybeFacilities: Option[SortedMap[PeerId, Facility]]): Option[SnapshotOrdinal] =
        maybeFacilities
          .map(_.values.map(_.lastGlobalSnapshotOrdinal).toList)
          .flatMap(pickMajority(_))

      private def toBinarySignaturesPhase(
        state: CurrencySnapshotConsensusState,
        status: CollectingSignatures,
        globalOrdinal: SnapshotOrdinal,
        signatures: SortedMap[PeerId, MajoritySignature]
      )(implicit hasher: Hasher[F]): F[Option[Transition]] = {
        val proofs = signatures.map { case (id, sig) => SignatureProof(PeerId._Id.get(id), sig.signature) }.toList

        for {
          valid <- proofs.filterA(verifySignatureProof(status.majorityArtifactInfo.hash, _))
          _ <- logInvalidSignatures(state.key, proofs.size, valid.size)
          role = if (selfId === state.leader) "LEADER" else "FOLLOWER"
          _ <- logger.info(
            s"[CONSENSUS:$role] SIGNATURES->BINARY_SIGNATURES key=${state.key.show} signatures=${valid.size}/${proofs.size} " +
              s"hash=${status.majorityArtifactInfo.hash.show.take(8)}... trigger=${status.majorityTrigger} globalOrdinal=${globalOrdinal.show} " +
              s"leader=${state.leader.show.take(8)}... self=${selfId.show.take(8)}... view=${state.viewNumber}"
          )
          result <- buildBinaryTransition(state, status, valid, globalOrdinal)
        } yield result
      }

      private def buildBinaryTransition(
        state: CurrencySnapshotConsensusState,
        status: CollectingSignatures,
        validSignatures: List[SignatureProof],
        globalOrdinal: SnapshotOrdinal
      )(implicit hasher: Hasher[F]): F[Option[Transition]] =
        state.facilitators.value.hash.flatMap { facilitatorsHash =>
          NonEmptySet.fromSet(validSignatures.toSortedSet).traverse { signaturesNes =>
            val signedArtifact = Signed(status.majorityArtifactInfo.artifact, signaturesNes)
            val stakingAddress = fetchStakingAddress(state.lastOutcome.finished.context.snapshotInfo)

            stateChannelSnapshotService
              .createBinary(signedArtifact, state.lastOutcome.finished.binaryArtifactHash, globalOrdinal.some, stakingAddress)
              .map { signedBinary =>
                Transition(
                  newState = state.copy(status =
                    CollectingBinarySignatures(
                      signedArtifact,
                      status.majorityArtifactInfo.context,
                      signedBinary.value,
                      status.majorityTrigger,
                      status.candidates,
                      facilitatorsHash,
                      state.lastOutcome.finished.snapshotHash
                    )
                  ),
                  sideEffect = spreadBinarySignature(
                    state,
                    state.key,
                    signedBinary.proofs.head.signature,
                    facilitatorsHash,
                    state.lastOutcome.finished.snapshotHash
                  )
                )
              }
          }
        }

      // =========================================================================
      // COLLECTING BINARY SIGNATURES → FINISHED
      // =========================================================================

      private def advanceFromBinarySignatures(
        state: CurrencySnapshotConsensusState,
        status: CollectingBinarySignatures,
        resources: ConsensusResources[CurrencySnapshotArtifact, CurrencyConsensusKind]
      ): F[Option[Transition]] =
        for {
          maybeBinarySignatures <- maybeGetAllDeclarations(state, resources)(_.binarySignature)
          // Skip facilitatorsHash fork check when view > 0 (eviction), solo→multi transition,
          // or during joining grace period (peer quality scores haven't converged yet).
          lastSolo3 <- wasLastRoundSolo
          inGrace3 <- nodeStorage.isInJoiningGracePeriod
          _ <- maybeBinarySignatures
            .traverse_(checkForkByFacilitatorsHash(_, status.facilitatorsHash)(_.facilitatorsHash))
            .whenA(!lastSolo3 && !inGrace3)
          _ <- maybeBinarySignatures.traverse_(checkForkByLastSnapshotHash(_, status.lastSnapshotHash))
          result <- maybeBinarySignatures.flatTraverse(toFinishedPhase(state, status, _))
        } yield result

      private def toFinishedPhase(
        state: CurrencySnapshotConsensusState,
        status: CollectingBinarySignatures,
        signatures: SortedMap[PeerId, BinarySignature]
      ): F[Option[Transition]] =
        HasherSelector[F].withCurrent { implicit hasher =>
          val proofs = signatures.map { case (id, bs) => SignatureProof(PeerId._Id.get(id), bs.signature) }.toList

          for {
            binaryHash <- status.binary.hash
            valid <- proofs.filterA(verifySignatureProof(binaryHash, _))
            _ <- logInvalidBinarySignatures(state.key, proofs.size, valid.size)
            role = if (selfId === state.leader) "LEADER" else "FOLLOWER"
            _ <- logger.info(
              s"[CONSENSUS:$role] BINARY_SIGNATURES->FINISHED key=${state.key.show} ordinal=${status.signedMajorityArtifact.ordinal.show} " +
                s"binarySignatures=${valid.size}/${proofs.size} binaryHash=${binaryHash.show.take(8)}... " +
                s"trigger=${status.majorityTrigger} leader=${state.leader.show.take(8)}... self=${selfId.show.take(8)}... view=${state.viewNumber}"
            )
            result <- buildFinishedTransition(state, status, valid)
          } yield result
        }

      private def buildFinishedTransition(
        state: CurrencySnapshotConsensusState,
        status: CollectingBinarySignatures,
        validSignatures: List[SignatureProof]
      )(implicit hasher: Hasher[F]): F[Option[Transition]] =
        for {
          facilitatorsHash <- state.facilitators.value.hash
          // Use the artifact hash (without signatures) for determinism across nodes.
          // signedMajorityArtifact.hash includes signatures, which can differ per node
          // when quorum < total, causing non-deterministic snapshotHash.
          snapshotHash <- status.signedMajorityArtifact.value.hash

          result <- NonEmptySet.fromSet(validSignatures.toSortedSet).traverse { signaturesNes =>
            val finalSignedBinary = Signed(status.binary, signaturesNes)
            finalSignedBinary.toHashed.map { hashedBinary =>
              Transition(
                newState = state.copy(status =
                  Finished(
                    status.signedMajorityArtifact,
                    hashedBinary.hash,
                    status.context,
                    status.majorityTrigger,
                    status.candidates,
                    facilitatorsHash,
                    snapshotHash
                  )
                ),
                sideEffect = persistAndGossip(status.signedMajorityArtifact, hashedBinary, state, status.context)
              )
            }
          }
        } yield result

      private def hashFacilitators(state: CurrencySnapshotConsensusState): F[Hash] =
        HasherSelector[F].withCurrent(implicit h => state.facilitators.value.hash)

      private def hashArtifact(artifact: CurrencySnapshotArtifact): F[Hash] =
        HasherSelector[F].withCurrent(implicit h => artifact.hash)

      private def createArtifact(
        state: CurrencySnapshotConsensusState,
        trigger: ConsensusTrigger,
        events: Set[CurrencySnapshotEvent]
      )(implicit hasher: Hasher[F]): F[(CurrencySnapshotArtifact, CurrencySnapshotContext, Set[CurrencySnapshotEvent])] =
        consensusFns.createProposalArtifact(
          state.key,
          state.lastOutcome.finished.signedMajorityArtifact,
          state.lastOutcome.finished.context,
          hasher,
          trigger,
          events,
          state.facilitators.value.toSet,
          getGlobalSnapshotByOrdinal
        )

      private val selfId: PeerId = PeerId.fromPublic(keyPair.getPublic)

      /** Spread proposal — only called by the leader. Uses direct push to all facilitators. */
      private def spreadProposal(
        state: CurrencySnapshotConsensusState,
        key: CurrencySnapshotKey,
        hash: Hash,
        facilitatorsHash: Hash,
        artifact: CurrencySnapshotArtifact,
        lastSnapshotHash: Hash,
        view: Long = 0L,
        vcc: Option[ViewChangeCertificate] = None
      ): F[Unit] = {
        val declaration = ConsensusPeerDeclaration(key, Proposal(hash, facilitatorsHash, lastSnapshotHash, view, vcc))
        val targets = state.facilitators.value.toSet

        gossip.spreadDirect(declaration, targets) >>
          gossip.spreadCommon(ConsensusArtifact(key, artifact))
      }

      private def spreadSignature(
        state: CurrencySnapshotConsensusState,
        key: CurrencySnapshotKey,
        signature: Signature,
        facilitatorsHash: Hash,
        lastSnapshotHash: Hash,
        view: Long,
        proposalHash: Hash
      ): F[Unit] = {
        val declaration =
          ConsensusPeerDeclaration(key, MajoritySignature(signature, facilitatorsHash, lastSnapshotHash, view, proposalHash))
        gossip.spreadDirect(declaration, state.facilitators.value.toSet)
      }

      private def spreadBinarySignature(
        state: CurrencySnapshotConsensusState,
        key: CurrencySnapshotKey,
        signature: Signature,
        facilitatorsHash: Hash,
        lastSnapshotHash: Hash
      ): F[Unit] = {
        val declaration = ConsensusPeerDeclaration(key, BinarySignature(signature, facilitatorsHash, lastSnapshotHash))
        gossip.spreadDirect(declaration, state.facilitators.value.toSet)
      }

      private def persistAndGossip(
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
          recordMetrics(signedArtifact, hashedBinary, context) >>
          gossipForkInfo(gossip, signedArtifact) >>
          notifyDataApplication(signedArtifact)

      private def recordMetrics(
        signed: Signed[CurrencySnapshotArtifact],
        hashedBinary: Hashed[StateChannelSnapshotBinary],
        context: CurrencySnapshotContext
      ): F[Unit] = {
        val metagraphTag: Metrics.TagSeq =
          Seq((Metrics.unsafeLabelName("metagraph_address"), context.address.show))

        // Blocks & transactions
        val allTransactions = signed.blocks.toList.flatMap(_.block.transactions.toList)
        val txCount = allTransactions.size
        val txAmountTotal = allTransactions.map(_.amount.value.value).sum
        val txFeeTotal = allTransactions.map(_.fee.value.value).sum

        // Rewards
        val rewardsCount = signed.rewards.size
        val rewardsAmountTotal = signed.rewards.toList.map(_.amount.value.value).sum

        // Tips
        val activeTips = signed.tips.remainedActive.size + signed.blocks.size
        val deprecatedTips = signed.tips.deprecated.size

        // Extended fields
        val messagesCount = signed.messages.map(_.size).getOrElse(0)
        val globalSnapshotSyncsCount = signed.globalSnapshotSyncs.map(_.size).getOrElse(0)
        val artifactsCount = signed.artifacts.map(_.size).getOrElse(0)

        // Fee transactions
        val feeTxList = signed.feeTransactions.map(_.toList).getOrElse(List.empty)
        val feeTransactionsCount = feeTxList.size
        val feeTransactionsAmountTotal = feeTxList.map(_.amount.value.value).sum

        // AllowSpend
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

        // Data application
        val dataAppOnChainStateBytes = signed.dataApplication.map(_.onChainState.length.toLong).getOrElse(0L)
        val dataAppBlocksCount = signed.dataApplication.map(_.blocks.size).getOrElse(0)
        val dataAppBlocksTotalBytes = signed.dataApplication.map(_.blocks.map(_.length.toLong).sum).getOrElse(0L)

        // Binary
        val binaryContentBytes = hashedBinary.content.length.toLong
        val binaryFee = hashedBinary.fee.value.value

        Metrics[F].updateGauge("dag_currency_snapshot_ordinal", signed.ordinal.value) >>
          Metrics[F].updateGauge("dag_currency_snapshot_height", signed.height.value) >>
          Metrics[F].updateGauge("dag_currency_snapshot_signature_count", signed.proofs.size) >>
          // Cumulative counters for value metrics (survive across scrapes unlike gauges)
          Metrics[F].incrementCounterBy("dag_currency_snapshot_blocks_total", signed.blocks.size) >>
          Metrics[F].incrementCounterBy("dag_currency_snapshot_transactions_total", txCount) >>
          Metrics[F].incrementCounterBy("dag_currency_snapshot_transaction_amount_cumulative", txAmountTotal) >>
          Metrics[F].incrementCounterBy("dag_currency_snapshot_transaction_fee_cumulative", txFeeTotal) >>
          Metrics[F].incrementCounterBy("dag_currency_snapshot_rewards_amount_cumulative", rewardsAmountTotal) >>
          Metrics[F].incrementCounterBy("dag_currency_snapshot_fee_transactions_amount_cumulative", feeTransactionsAmountTotal) >>
          Metrics[F].incrementCounterBy("dag_currency_snapshot_allow_spend_amount_cumulative", allowSpendAmountTotal) >>
          Metrics[F].incrementCounterBy("dag_currency_snapshot_allow_spend_fee_cumulative", allowSpendFeeTotal) >>
          Metrics[F].incrementCounterBy("dag_currency_snapshot_token_lock_amount_cumulative", tokenLockAmountTotal) >>
          Metrics[F].incrementCounterBy("dag_currency_snapshot_token_lock_fee_cumulative", tokenLockFeeTotal) >>
          Metrics[F].incrementCounterBy("dag_currency_snapshot_binary_fee_cumulative", binaryFee) >>
          // Blocks & transactions - counts and values
          Metrics[F].updateGauge("dag_currency_snapshot_incremental_blocks_count", signed.blocks.size) >>
          Metrics[F].updateGauge("dag_currency_snapshot_incremental_transactions_count", txCount) >>
          Metrics[F].updateGauge("dag_currency_snapshot_incremental_transaction_amount_total", txAmountTotal) >>
          Metrics[F].updateGauge("dag_currency_snapshot_incremental_transaction_fee_total", txFeeTotal) >>
          // Rewards - counts and values
          Metrics[F].updateGauge("dag_currency_snapshot_incremental_rewards_count", rewardsCount) >>
          Metrics[F].updateGauge("dag_currency_snapshot_incremental_rewards_amount_total", rewardsAmountTotal) >>
          // Tips
          Metrics[F].updateGauge("dag_currency_snapshot_incremental_tips_count", activeTips, Seq(("tip_type", "active"))) >>
          Metrics[F].updateGauge("dag_currency_snapshot_incremental_tips_count", deprecatedTips, Seq(("tip_type", "deprecated"))) >>
          // Extended fields
          Metrics[F].updateGauge("dag_currency_snapshot_incremental_messages_count", messagesCount) >>
          Metrics[F].updateGauge("dag_currency_snapshot_incremental_global_snapshot_syncs_count", globalSnapshotSyncsCount) >>
          Metrics[F].updateGauge("dag_currency_snapshot_incremental_artifacts_count", artifactsCount) >>
          // Fee transactions - counts and values
          Metrics[F].updateGauge("dag_currency_snapshot_incremental_fee_transactions_count", feeTransactionsCount) >>
          Metrics[F].updateGauge("dag_currency_snapshot_incremental_fee_transactions_amount_total", feeTransactionsAmountTotal) >>
          // AllowSpend - counts, amounts, fees
          Metrics[F].updateGauge("dag_currency_snapshot_incremental_allow_spend_blocks_count", allowSpendBlocksCount) >>
          Metrics[F].updateGauge("dag_currency_snapshot_incremental_allow_spend_tx_count", allowSpendTxCount) >>
          Metrics[F].updateGauge("dag_currency_snapshot_incremental_allow_spend_amount_total", allowSpendAmountTotal) >>
          Metrics[F].updateGauge("dag_currency_snapshot_incremental_allow_spend_fee_total", allowSpendFeeTotal) >>
          // TokenLock - counts, amounts, fees
          Metrics[F].updateGauge("dag_currency_snapshot_incremental_token_lock_blocks_count", tokenLockBlocksCount) >>
          Metrics[F].updateGauge("dag_currency_snapshot_incremental_token_lock_tx_count", tokenLockTxCount) >>
          Metrics[F].updateGauge("dag_currency_snapshot_incremental_token_lock_amount_total", tokenLockAmountTotal) >>
          Metrics[F].updateGauge("dag_currency_snapshot_incremental_token_lock_fee_total", tokenLockFeeTotal) >>
          // Data application sizes
          Metrics[F].updateGauge("dag_currency_snapshot_incremental_data_app_onchain_state_bytes", dataAppOnChainStateBytes) >>
          Metrics[F].updateGauge("dag_currency_snapshot_incremental_data_app_blocks_count", dataAppBlocksCount) >>
          Metrics[F].updateGauge("dag_currency_snapshot_incremental_data_app_blocks_total_bytes", dataAppBlocksTotalBytes) >>
          // Binary size and fee
          Metrics[F].updateGauge("dag_currency_snapshot_binary_content_bytes", binaryContentBytes) >>
          Metrics[F].updateGauge("dag_currency_snapshot_binary_fee", binaryFee)
      }

      private def notifyDataApplication(signedArtifact: Signed[CurrencySnapshotArtifact]): F[Unit] =
        maybeDataApplication.traverse_ { da =>
          HasherSelector[F].withCurrent(implicit h => signedArtifact.toHashed) >>= da.onSnapshotConsensusResult
        }.handleErrorWith(logger.error(_)("Unhandled exception during onSnapshotConsensusResult"))

      private def checkForkByLastSnapshotHash[A](declarations: SortedMap[PeerId, A], ownHash: Hash)(
        implicit extract: A => Hash
      ): F[Unit] =
        recoverIfForking[F](ownHash, lastSnapshotHashObservationName, nodeStorage)(
          declarations.map { case (pid, decl) => (pid, extract(decl)) }
        )

      /** Skip facilitatorsHash fork check when transitioning from solo genesis (facilitators=1) to multi-node consensus. PeerQualityTracker
        * penalty state is node-local, causing different facilitatorsHash values on the first multi-node round.
        */
      private def wasLastRoundSolo: F[Boolean] =
        consensusStorage.getLastConsensusOutcome.map {
          case Some(outcome) => outcome.facilitators.value.size <= 1
          case None          => true
        }

      private def checkForkByFacilitatorsHash[A](
        declarations: SortedMap[PeerId, A],
        ownHash: Hash
      )(extractHash: A => Hash): F[Unit] =
        recoverIfForking[F](ownHash, facilitatorsHashObservationName, nodeStorage)(
          declarations.map { case (pid, decl) => (pid, extractHash(decl)) }
        )

      private def checkForkByConsensusConfigHash(facilities: SortedMap[PeerId, Facility]): F[Unit] = {
        val ownConfigHash = config.deterministicConfigHash
        val peerConfigHashes = facilities.flatMap {
          case (pid, f) => f.consensusConfigHash.map(pid -> _)
        }
        if (peerConfigHashes.nonEmpty)
          recoverIfForking[F](ownConfigHash, consensusConfigHashObservationName, nodeStorage)(
            SortedMap.from(peerConfigHashes)
          )
        else Applicative[F].unit
      }

      private implicit val extractFacilityHash: Facility => Hash = _.lastSnapshotHash
      private implicit val extractProposalHash: Proposal => Hash = _.lastSnapshotHash
      private implicit val extractSignatureHash: MajoritySignature => Hash = _.lastSnapshotHash
      private implicit val extractBinarySignatureHash: BinarySignature => Hash = _.lastSnapshotHash

      private def clearTimeTriggerIfNeeded(trigger: ConsensusTrigger): F[Unit] =
        Applicative[F].whenA(trigger === TimeTrigger)(consensusStorage.clearTimeTrigger)

      private def recordProposalAffinity(allHashes: List[Hash], ownHash: Hash): F[Unit] =
        Metrics[F].recordDistribution("dag_consensus_proposal_affinity", proposalAffinity(allHashes, ownHash))

      private def logInvalidSignatures(key: CurrencySnapshotKey, total: Int, valid: Int): F[Unit] =
        logger
          .warn(s"Removed ${total - valid} invalid signatures for key=${key.show}, $valid valid remaining")
          .whenA(total != valid)

      private def logInvalidBinarySignatures(key: CurrencySnapshotKey, total: Int, valid: Int): F[Unit] =
        logger
          .warn(s"Removed ${total - valid} invalid binary signatures for key=${key.show}, $valid valid remaining")
          .whenA(total != valid)
    }
}
