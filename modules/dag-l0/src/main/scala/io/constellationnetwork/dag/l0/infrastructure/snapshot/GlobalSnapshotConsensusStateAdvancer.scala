package io.constellationnetwork.dag.l0.infrastructure.snapshot

import java.security.KeyPair
import java.time.Instant

import cats.data.{NonEmptySet, StateT}
import cats.effect.{Async, Outcome, Ref}
import cats.syntax.all._
import cats.{Applicative, MonadThrow, Parallel}

import scala.collection.immutable.{SortedMap, SortedSet}
import scala.concurrent.duration.{FiniteDuration, _}

import io.constellationnetwork.dag.l0.infrastructure.mempool.DagAwaitingParentConfig
import io.constellationnetwork.dag.l0.infrastructure.snapshot.event._
import io.constellationnetwork.dag.l0.infrastructure.snapshot.schema._
import io.constellationnetwork.ext.collection.FoldableOps.pickMajority
import io.constellationnetwork.ext.crypto._
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.node.shared.config.types.ConsensusConfig
import io.constellationnetwork.node.shared.domain.cluster.storage.ClusterStorage
import io.constellationnetwork.node.shared.domain.consensus.ConsensusFunctions.InvalidArtifact
import io.constellationnetwork.node.shared.domain.gossip.Gossip
import io.constellationnetwork.node.shared.domain.node.NodeStorage
import io.constellationnetwork.node.shared.domain.snapshot.storage.{LastNGlobalSnapshotStorage, LastSnapshotStorage, SnapshotStorage}
import io.constellationnetwork.node.shared.infrastructure.consensus.ConsensusLog.{Category, Event}
import io.constellationnetwork.node.shared.infrastructure.consensus._
import io.constellationnetwork.node.shared.infrastructure.consensus.declaration._
import io.constellationnetwork.node.shared.infrastructure.consensus.message._
import io.constellationnetwork.node.shared.infrastructure.consensus.state.ConsensusStateUpdater._
import io.constellationnetwork.node.shared.infrastructure.consensus.state._
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.{ConsensusTrigger, EventTrigger, TimeTrigger}
import io.constellationnetwork.node.shared.infrastructure.fork.ExitOnFork
import io.constellationnetwork.node.shared.infrastructure.gossip.event.{EventGossipClient, IWantRequest}
import io.constellationnetwork.node.shared.infrastructure.mempool.EventMempool
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.node.shared.infrastructure.node.RestartService
import io.constellationnetwork.node.shared.infrastructure.selfhealth.SelfHealthHint
import io.constellationnetwork.node.shared.infrastructure.snapshot.GlobalArtifactMismatch
import io.constellationnetwork.node.shared.infrastructure.snapshot.storage.PeerHistorySidecarStorage
import io.constellationnetwork.node.shared.logger.LoggerBundle
import io.constellationnetwork.schema.gossip.Ordinal
import io.constellationnetwork.schema.mpt.GlobalStateConverter.syntax._
import io.constellationnetwork.schema.mpt.{GlobalStateKey, MptStore, MptStoreSavepoint}
import io.constellationnetwork.schema.node.NodeState
import io.constellationnetwork.schema.peer.{Peer, PeerId}
import io.constellationnetwork.schema.{GlobalStateProofSelector, _}
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.signature._
import io.constellationnetwork.statechannel.StateChannelOutput
import io.constellationnetwork.syntax.sortedCollection._

import eu.timepit.refined.auto._
import io.circe.{Encoder, Json}
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

  def make[F[_]: Async: Parallel: SecurityProvider: Metrics: HasherSelector: JsonSerializer](
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
    eventMempool: EventMempool[F, GlobalSnapshotEvent, GlobalStateKey],
    eventGossipClient: EventGossipClient[F, GlobalSnapshotEvent],
    loggerBundle: LoggerBundle[F],
    mptStore: MptStore[F, GlobalStateKey],
    facilitatorSelector: FacilitatorSelector,
    // Alpha.94: best-effort node-local cache of `Outcome[N].toOperationalState` keyed by snapshot ordinal.
    // Written after each successful `persistAndGossip` so a future rollback to N seeds `state.lastOutcome`
    // from the post-finalization view instead of the one-round-stale `snapshot.peerHistory` field
    // (see `PeerHistorySidecarStorage` scaladoc + `project_alpha92_wedge_may21.md`).
    peerHistorySidecar: PeerHistorySidecarStorage[F]
  )(implicit globalStateProofSelector: GlobalStateProofSelector): GlobalSnapshotConsensusStateAdvancer[F] =
    new GlobalSnapshotConsensusStateAdvancer[F] {

      private val logger: SelfAwareStructuredLogger[F] = Slf4jLogger.getLoggerFromClass[F](getClass)

      /** Savepoint taken before `createArtifact()` mutations. On round abandonment + retry at the same ordinal, this is restored before
        * re-building the proposal to ensure the MptStore starts from a clean pre-mutation state.
        *
        * Tracks the key (ordinal) alongside the savepoint so that stale savepoints from a different ordinal (e.g., after recovery download)
        * are discarded instead of restored — restoring a savepoint from ordinal N into an MptStore that was replaced by a download at
        * ordinal M would corrupt state.
        *
        * TODO: Consider clearing proposalSavepointRef explicitly in round cleanup to strengthen the invariant. Currently the ordinal-check
        * guard prevents stale restore, but explicit cleanup would make the lifecycle more defensive against theoretical races during
        * recovery (two rounds racing).
        */
      private val proposalSavepointRef: Ref[F, Option[(GlobalSnapshotKey, MptStoreSavepoint[F])]] = Ref.unsafe(none)

      // Tracks the monotonic timestamp at which majority-signature quorum was first
      // reached for each round key. Consumed by `buildFinishedTransition` to delay
      // finalization by `config.signatureGracePeriod` when the quorum set is smaller
      // than the full committee — catches late signatures that would otherwise be
      // dropped from `signedArtifact.proofs`. Entries are cleared at finalize; map
      // growth is bounded by the active round cone since abandoned rounds shed
      // entries on the next finalize at or past their key (and resources are
      // otherwise reset on round cleanup).
      private val signatureQuorumFirstSeenRef: Ref[F, Map[GlobalSnapshotKey, SignatureGraceDecision.Stamp]] = Ref.unsafe(Map.empty)

      /** Tracks consecutive validation failures (stateProofDiffers) at the same ordinal. When a node's local state diverges (e.g., after
        * network isolation), every validation attempt fails with the same MPT root mismatch. Neither consensus fork detection (requires
        * completed round) nor gossip fork detection (compares hashes at same ordinal, but node is 1 behind) catches this. After
        * `maxConsecutiveValidationFailures` failures, trigger an incremental recovery via `nodeStorage.setRecoveryDownload`; the
        * incremental path resyncs MptStore from the downloaded snapshot's checkpoint data, which is sufficient to clear the divergent MPT
        * state without requiring a full re-download from genesis.
        */
      private val validationFailureCountRef: Ref[F, (Option[GlobalSnapshotKey], Int)] = Ref.unsafe((none, 0))
      private val maxConsecutiveValidationFailures: Int = 3

      /** Tracks the most recent divergent majority hash observed against this node's own hash, keyed by observation type. Read by
        * `recoverIfForking` to enforce a `forkConfirmationWindow` persistence requirement before flipping to `WaitingForDownload`. Clears
        * on first non-forked sample, on majority-hash flip, or on confirmation. See `recoverIfForking` docstring for the full state
        * machine.
        */
      private val forkObservationsRef: Ref[F, Map[ConsensusStateUpdater.ForkObservation, (Hash, FiniteDuration)]] =
        Ref.unsafe(Map.empty)

      protected val clusterStorage: ClusterStorage[F] = clusterStorageInstance
      protected val config: ConsensusConfig = consensusConfig

      private case class Transition(newState: GlobalSnapshotConsensusState, sideEffect: F[Unit])

      private def tierName(tier: Int): String =
        tier match {
          case TierTransitions.Core    => "core"
          case TierTransitions.Tier1   => "tier1"
          case TierTransitions.Witness => "witness"
          case _                       => "unknown"
        }

      private def nextPeerTiersForFinished(state: GlobalSnapshotConsensusState): SortedMap[PeerId, Int] = {
        // MUST stay in lockstep with the `newRecentSigners` window built at outcome
        // finalization (getConsensusOutcome): the same canonical signer set, sourced only
        // from proposal-carried + round-start-frozen data. See
        // `ControllerEvidenceDerivation.canonicalCompletedSigners` for the determinism
        // argument (`state.removedFacilitators` is NOT an allowed input).
        val canonicalSigners = ControllerEvidenceDerivation.canonicalCompletedSigners(
          roundStartFacilitators = SortedSet.from(state.roundStartFacilitators.value),
          acceptedObservedResponders = state.observedResponders.value,
          certifiedEvictions = state.certifiedEvictionTargets
        )
        val currentOrdValue = state.key.value.value
        val tighteningMinOrdinalValue =
          math.max(0L, currentOrdValue - config.tighteningWindow.toLong + 1L)
        val newRecentSigners =
          state.lastOutcome.recentSigners
            .updated(state.key, canonicalSigners)
            .filter { case (ord, _) => ord.value.value >= tighteningMinOrdinalValue }

        TierTransitions.computeNextTiers(
          priorTiers = state.lastOutcome.peerTiers,
          roundStartFacilitators = state.roundStartFacilitators.value.toSet,
          recentSignersWindow = newRecentSigners,
          roundCompleted = true
        )
      }

      private def recordPeerTierMetrics(
        priorPeerTiers: SortedMap[PeerId, Int],
        nextPeerTiers: SortedMap[PeerId, Int]
      ): F[Unit] = {
        val tierLabel = Metrics.unsafeLabelName("tier")
        val fromTierLabel = Metrics.unsafeLabelName("from_tier")
        val toTierLabel = Metrics.unsafeLabelName("to_tier")
        val reasonLabel = Metrics.unsafeLabelName("reason")

        val tierCounts =
          nextPeerTiers.valuesIterator.toList.groupMapReduce(identity)(_ => 1)(_ + _)
        val tierTransitions =
          nextPeerTiers.toList.flatMap {
            case (pid, nextTier) =>
              val priorTier = priorPeerTiers.getOrElse(pid, TierTransitions.Tier1)
              Option.when(priorTier =!= nextTier) {
                val reason =
                  if (priorTier === TierTransitions.Core && nextTier === TierTransitions.Tier1) "sustained_silence"
                  else "classification_changed"
                (tierName(priorTier), tierName(nextTier), reason)
              }
          }

        def updateTierGauge(tier: Int): F[Unit] =
          Metrics[F].updateGauge(
            "dag_consensus_peer_tier_size",
            tierCounts.getOrElse(tier, 0).toLong,
            Seq(tierLabel -> tierName(tier))
          )

        updateTierGauge(TierTransitions.Core) >>
          updateTierGauge(TierTransitions.Tier1) >>
          updateTierGauge(TierTransitions.Witness) >>
          Metrics[F].updateGauge("dag_consensus_peer_tier_tracked_size", nextPeerTiers.size.toLong) >>
          Metrics[F].updateGauge("dag_consensus_peer_tier_transition_count", tierTransitions.size.toLong) >>
          tierTransitions.traverse_ {
            case (from, to, reason) =>
              Metrics[F].incrementCounter(
                "dag_consensus_peer_tier_transition_total",
                Seq(fromTierLabel -> from, toTierLabel -> to, reasonLabel -> reason)
              )
          }
      }

      override def isBootstrapActive(lastOutcome: GlobalConsensusOutcome): Boolean =
        !lastOutcome.recentProofSizes.values.exists(_ >= config.bootstrapCompleteProofsThreshold)

      // v33 quorum-denominator shrink anchors (see QuorumDenominatorShrink). Both are
      // consensus-agreed outcome fields: the latest controllerEvidence entry's canonical
      // completedSigners and the parent round's facility-median consensusEndTime.
      override protected def latestEvidenceSigners(lastOutcome: GlobalConsensusOutcome): Option[SortedSet[PeerId]] =
        lastOutcome.controllerEvidence.flatMap(_.lastOption).map { case (_, entry) => entry.completedSigners }

      override protected def lastOutcomeEndTimeMs(lastOutcome: GlobalConsensusOutcome): Option[Long] =
        lastOutcome.recentRoundEndTimes.lastOption.map { case (_, endTime) => endTime }

      // v4.1.0 cluster-majority floor: enable the committee-supermajority finality floor outside bootstrap
      // (see QuorumDenominatorShrink.decide / ConsensusStateAdvancer.clusterFloorActive). isInBootstrap is
      // derived from consensus-agreed recentProofSizes, so this is deterministic across nodes.
      override protected def clusterFloorActive(state: GlobalSnapshotConsensusState): Boolean =
        !isInBootstrap(state)

      def getConsensusOutcome(
        state: GlobalSnapshotConsensusState
      ): Option[(Previous[GlobalSnapshotKey], GlobalConsensusOutcome)] =
        state.status match {
          case f: Finished =>
            // Phase 3: derive penalty/quality state from CONSENSUS-AGREED inputs only.
            //
            // Prior implementation derived `signers` from `f.signedMajorityArtifact.proofs`,
            // but `proofs` varies across nodes for the same artifact/ordinal: each node
            // finalizes the round the instant it observes quorum's worth of MajoritySignature
            // declarations (per `maybeGetAllDeclarations`). Fast finalizers stop at exactly
            // quorum; slow finalizers accumulate extra signatures. `SnapshotStorage.prepend`
            // does NOT merge proofs from later-arriving gossip copies (see SnapshotStorage.scala
            // `isNextSnapshot` / head-replace logic). `ForkInfo` gossip only carries
            // `(ordinal, hash)` — no proofs. So there is no cluster-wide convergence path for
            // `proofs.size`.
            //
            // With non-deterministic `signers`, two nodes can compute different
            // `nonSigners = facilitators - signers` → different `penalizedThisRound` →
            // divergent `removalPenalties`, `cumulativeMissCounts`, and `peerQuality`
            // fields in the stored `lastOutcome`. Those fields gate `chronicNonSigners`,
            // `penalizedPeers`, and deferral filtering in the NEXT round's state creator,
            // which produces divergent facilitator sets and cascades into `facilitatorsHash`
            // fork checks.
            //
            // Fix: compute `penalizedThisRound` using only `state.removedFacilitators` —
            // peers evicted by the consensus-agreed facility fork-eviction path (see
            // `advanceFromFacilities`). That set is deterministic across all nodes that
            // complete the round (they agree on `facilitatorsHash` or cannot finalize).
            // The slow-signer penalty signal is dropped deliberately: Phase 2's VoteLock
            // plus quorum-certified view change already contain the safety consequences
            // of slow peers; stall-cycle abandonment handles liveness.
            //
            // For `peerQuality`, credit every non-evicted facilitator with
            // `(completed=1, participated=1)`: reaching Finished implies the committee
            // reached quorum, and the individual signer/non-signer split within the
            // committee is not consensus-agreed. Evicted facilitators get
            // `(completed=0, participated=1)` so they remain trackable but don't gain
            // quality score while out.
            //
            // Grace window: peers whose `deferralCountdown > 0` are in the post-Ready
            // observation period. Their local advancer can lag by seconds on the first
            // rounds they're selected into (MPT warmup, acceptance pipeline cold start),
            // which caused a cascade where a freshly-Ready peer misses round N's
            // signature window, gets penalized, sits out rounds N+1..N+2, then
            // re-enters round N+3 still behind — stalling the whole cluster. Symmetric
            // suppression: they don't accrue `participated` OR `completed` during grace.
            // This uses the same consensus-agreed `deferralCountdown` infrastructure
            // that already gates active facilitation (state creator), so every node
            // arrives at the same peerQuality map.
            val evictedPeers = state.removedFacilitators.value
            val previousPenalties = state.lastOutcome.removalPenalties
            val previousCumulative = state.lastOutcome.cumulativeMissCounts

            // v19 cleanup: the deferralCountdown field is now inert (StateCreator no longer
            // reads it), so there is no "deferral bypass" cohort to exclude from penalty.
            // Every non-evicted facilitator participates fully in penalty accounting.
            val deferredInCommittee = Set.empty[PeerId]

            // Decay: every non-evicted facilitator earns a 1-unit credit against their
            // cumulative miss count. Prevents the exponential penalty formula from
            // trapping nodes forever once they've signed cleanly across enough rounds.
            //
            // Uses roundStartFacilitators (canonical) not state.facilitators (mutable):
            // mid-round withdrawals mutate state.facilitators on nodes that observed the
            // withdrawal pre-finish, diverging completedFacilitators across nodes and
            // ultimately the deferralCountdown in lastOutcome. That was the
            // ord-4->5 fork (see .workspace/codex-response-ord5-facilitator-fork-apr23.md).
            val completedFacilitators = state.roundStartFacilitators.value.toSet -- evictedPeers
            val decayedCumulative = completedFacilitators.foldLeft(previousCumulative) { (acc, pid) =>
              acc.get(pid) match {
                case Some(v) if v > 1L => acc.updated(pid, v - 1L)
                case Some(_)           => acc - pid // reached 0 — prune so the map stays bounded
                case None              => acc // no prior miss history, nothing to decay
              }
            }

            // Bootstrap warmup: classify the chain as post-bootstrap once a recent round
            // has committee size >= bootstrapCompleteProofsThreshold. Uses
            // `state.facilitators.value.size` (consensus-agreed) rather than
            // `f.signedMajorityArtifact.proofs.size` (local-observed) so all nodes reach
            // the same bootstrap/post-bootstrap classification deterministically.
            val isInBootstrap =
              !state.lastOutcome.recentProofSizes.values.exists(_ >= config.bootstrapCompleteProofsThreshold)

            // Penalize only consensus-agreed evictions (facility fork-eviction).
            val penalizedThisRound =
              if (isInBootstrap) Set.empty[PeerId] else (evictedPeers -- deferredInCommittee).toSet
            val newCumulative = penalizedThisRound.foldLeft(decayedCumulative) { (acc, pid) =>
              acc.updated(pid, acc.getOrElse(pid, 0L) + 1L)
            }

            val decrementedPenalties = previousPenalties.view.mapValues(_ - 1).filter(_._2 > 0).to(SortedMap)
            val newPenalties = penalizedThisRound.foldLeft(decrementedPenalties) { (acc, pid) =>
              val repeatCount = newCumulative.getOrElse(pid, 1L) - 1L // first eviction = exponent 0
              // penalty = removalPenaltyRounds * base^repeatCount, clamped to maxRemovalPenaltyRounds
              val base = config.exponentialPenaltyBase.toDouble
              val scaled = config.removalPenaltyRounds.toDouble * math.pow(base, repeatCount.toDouble)
              val penalty = math.min(scaled, config.maxRemovalPenaltyRounds.toDouble).toInt
              acc.updated(pid, math.max(1, penalty))
            }
            val finalPenalties = if (config.removalPenaltyRounds > 0) newPenalties else SortedMap.empty[PeerId, Int]

            // v19 cleanup: deferralCountdown is inert (no StateCreator consumer). justUnpenalized
            // is still computed because it seeds the B2 readmissionCountdown path below --
            // rejoiners whose removalPenalty just expired enter probation and wait for a
            // quorum-witnessed AdmissionCertificate. The deferralCountdown field is written
            // as empty going forward (see outcome construction below).
            val justUnpenalized = previousPenalties.filter(_._2 == 1).keySet

            // v7 (flaky-byzantine): the peerQuality "completed" signal now reflects ACTUAL
            // facility-phase participation, not "non-fork-evicted" as it did before. Source is
            // the leader's signed observedResponders carried on the accepted Proposal and
            // plumbed onto state via REPLACE-on-accept at buildSignatureTransition. Bound to
            // the leader by the rumor envelope's signature (RumorValidator.scala:50). Under
            // flaky-byzantine, leaders honestly report; under bootstrap, fall back to today's
            // "non-evicted = completed" semantic to avoid falsely classifying cold-start peers
            // as chronic before they've had a chance to participate.
            val responderSet: Set[PeerId] =
              if (isInBootstrap) completedFacilitators
              else state.observedResponders.value
            val thisRoundQuality: SortedMap[PeerId, (Int, Int)] = SortedMap.from(
              // Iterate canonical committee — mid-round withdrawals must not change
              // which peers have a peerQuality row for this round.
              state.roundStartFacilitators.value
                .filterNot(deferredInCommittee.contains)
                .map { pid =>
                  val completed = if (responderSet.contains(pid)) 1 else 0
                  pid -> (completed, 1)
                }
            )
            // Accumulate with previous rounds; decay/prune as before.
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
            val decayed =
              if (needsDecay) rawAccumulated.view.mapValues { case (c, p) => (c / 2, p / 2) }.to(SortedMap)
              else rawAccumulated
            val accumulatedQuality = decayed.filter { case (_, (c, p)) => c > 0 || p > 0 }

            // Canonical (node-independent) committee and completed-signer set for the
            // just-finalized round. These feed the SIGNED-bytes windows below
            // (recentProofSizes / recentSigners / controllerEvidence carried via
            // signedArtifactPeerHistory), so they must be byte-identical on every node
            // deciding this round. Unlike `completedFacilitators` above (which subtracts
            // `state.removedFacilitators`, whose facility-phase fork-eviction component is
            // computed from the LOCAL declaration snapshot at quorum-crossing and diverges
            // across honest nodes -- the ordinal-3150166 controllerEvidenceDiffer wedge),
            // these are derived ONLY from round-start-frozen and quorum-accepted-proposal
            // data. Full determinism argument:
            // `ControllerEvidenceDerivation.canonicalCompletedSigners`.
            val canonicalCommitteeForRound: SortedSet[PeerId] =
              ControllerEvidenceDerivation.canonicalCommittee(
                roundStartFacilitators = SortedSet.from(state.roundStartFacilitators.value),
                certifiedEvictions = state.certifiedEvictionTargets
              )
            val canonicalSigners: SortedSet[PeerId] =
              ControllerEvidenceDerivation.canonicalCompletedSigners(
                roundStartFacilitators = SortedSet.from(state.roundStartFacilitators.value),
                acceptedObservedResponders = state.observedResponders.value,
                certifiedEvictions = state.certifiedEvictionTargets
              )

            // Roll the proofs-size window forward using the canonical committee size for
            // the completed round (NOT `f.signedMajorityArtifact.proofs.size`, locally
            // observed; NOT `completedFacilitators.size`, which embeds local fork-eviction
            // observations). Committee-size semantics are kept (rather than responder
            // count) so the bootstrap classification keyed on
            // `bootstrapCompleteProofsThreshold` continues to measure committee size.
            val bootstrapLookbackOrdinals = 10L
            val currentOrdValue = state.key.value.value
            val minOrdinalValue = math.max(0L, currentOrdValue - bootstrapLookbackOrdinals)
            val currentProofsSize: Int = canonicalCommitteeForRound.size
            val newRecentProofSizes: SortedMap[SnapshotOrdinal, Int] = {
              val withCurrent =
                state.lastOutcome.recentProofSizes.updated(state.key, currentProofsSize)
              withCurrent.filter { case (ord, _) => ord.value.value >= minOrdinalValue }
            }

            // v22: recentSigners is repopulated as the rolling K-round signer-set window and is now
            // the input to the tier-demotion hysteresis (TierTransitions.DemotionConsecutiveMisses).
            // Append the just-completed round's CANONICAL signer set and trim to the tightening
            // window. The map is SortedMap[SnapshotOrdinal, SortedSet[PeerId]] -- fully sorted, so
            // it serializes order-independently (ArtifactSerializationDeterminismSuite covers
            // exactly this field) and every honest node writes byte-identical bytes. Same
            // window-trim arithmetic the recentProofSizes / recentRoundEndTimes windows use.
            // MUST stay in lockstep with `nextPeerTiersForFinished`, which rebuilds the same
            // window for the peerTiers computation.
            val tighteningMinOrdinalValue =
              math.max(0L, currentOrdValue - config.tighteningWindow.toLong + 1L)
            val newRecentSigners: SortedMap[SnapshotOrdinal, SortedSet[PeerId]] = {
              val withCurrent =
                state.lastOutcome.recentSigners.updated(state.key, canonicalSigners)
              withCurrent.filter { case (ord, _) => ord.value.value >= tighteningMinOrdinalValue }
            }

            // v19/v22 multi-committee tier transitions. Round completed (we are in `Finished`), so a
            // Tier 2 peer in `roundStartFacilitators` demotes to Tier 1 ONLY if it has been absent
            // from the most-recent `DemotionConsecutiveMisses` signer sets (sustained silence), not
            // on a single missed signature -- the hysteresis that makes the lowered Core floor safe.
            // Inputs are all consensus-agreed deterministic outcome fields so the computation is
            // byte-identical across honest nodes.
            val newPeerTiers: SortedMap[PeerId, Int] = nextPeerTiersForFinished(state)

            // v19 phase 2: append the round's `consensusEndTime` to the sliding window if
            // it was computed (Facility set carried enough `proposerClockMs` to clear the
            // strict-majority threshold). Otherwise the round produced no time anchor and
            // the window carries forward unchanged; consume-site falls back to phase 1.
            val newRecentRoundEndTimes: SortedMap[SnapshotOrdinal, Long] =
              state.outcomeEndTime match {
                case Some(endTime) =>
                  val withCurrent = state.lastOutcome.recentRoundEndTimes.updated(state.key, endTime)
                  withCurrent.filter { case (ord, _) => ord.value.value >= tighteningMinOrdinalValue }
                case None =>
                  state.lastOutcome.recentRoundEndTimes.filter { case (ord, _) => ord.value.value >= tighteningMinOrdinalValue }
              }
            val newActiveAdmissionScores: SortedMap[PeerId, Int] =
              ConsensusPeerController.advanceScores(
                prior = state.lastOutcome.activeAdmissionScores,
                evidence = ConsensusPeerController.RoundEvidence(
                  roundStart = state.roundStartFacilitators.value.toSet,
                  completed = completedFacilitators,
                  responders = responderSet,
                  timeoutVoters = state.acceptedTimeoutCertificateVoters.toSet,
                  evicted = evictedPeers,
                  observedSelfHealth = state.observedSelfHealth.value
                ),
                config = ConsensusPeerController.Config(
                  promoteThreshold = config.activeAdmissionPromoteThreshold,
                  retainThreshold = config.activeAdmissionRetainThreshold,
                  demoteThreshold = config.activeAdmissionDemoteThreshold,
                  maxScore = config.activeAdmissionMaxScore,
                  signatureReward = config.activeAdmissionSignatureReward,
                  responderReward = config.activeAdmissionResponderReward,
                  missedActivePenalty = config.activeAdmissionMissedActivePenalty,
                  timeoutMissingPenalty = config.activeAdmissionTimeoutMissingPenalty,
                  evictedPenalty = config.activeAdmissionEvictedPenalty,
                  degradedPenalty = config.activeAdmissionDegradedPenalty,
                  criticalPenalty = config.activeAdmissionCriticalPenalty,
                  passiveDecay = config.activeAdmissionPassiveDecay,
                  maxExpansionPerRound = config.activeAdmissionMaxExpansionPerRound
                )
              )
            // Controller evidence stage 1: append the just-finalized round's canonical facts to
            // the bounded evidence window. Every input is consensus-agreed at this site:
            // roundStartFacilitators is the frozen canonical committee, canonicalSigners is the
            // proposal-carried completed-signer set shared with the recentSigners window (NOT
            // the local-observed proofs set and NOT `roundStart -- removedFacilitators`, whose
            // fork-eviction component is node-local -- see
            // ControllerEvidenceDerivation.canonicalCompletedSigners for the determinism
            // argument), acceptedTimeoutCertificateVoters comes from the accepted proposal's
            // embedded TC, and admitted/certifiedEvicted are certificate-applied targets stashed
            // at buildSignatureTransition.
            val controllerEvidenceEntry = ControllerEvidenceEntry(
              roundStartFacilitators = SortedSet.from(state.roundStartFacilitators.value),
              completedSigners = canonicalSigners,
              timeoutVoters = state.acceptedTimeoutCertificateVoters,
              admittedPeers = SortedSet.from(state.admittedFacilitators.value),
              evictedPeers = state.certifiedEvictionTargets
            )
            val newControllerEvidence: SortedMap[SnapshotOrdinal, ControllerEvidenceEntry] =
              ControllerEvidenceDerivation.appendBounded(
                prior = state.lastOutcome.controllerEvidence.getOrElse(SortedMap.empty),
                key = state.key,
                entry = controllerEvidenceEntry,
                tighteningWindow = config.tighteningWindow
              )
            // Controller evidence stage 3: cert-anchored penalty horizons. Entries are written
            // only for certificate-applied evictions (N + penaltyDurationOrdinals), cleared by
            // certificate-applied admissions, and expired by pure ordinal comparison. Write-only
            // for now -- no consumer reads penaltyUntil yet.
            val newPenaltyUntil: SortedMap[PeerId, SnapshotOrdinal] =
              ControllerEvidenceDerivation.nextPenaltyUntil(
                prior = state.lastOutcome.penaltyUntil.getOrElse(SortedMap.empty),
                certifiedEvictions = state.certifiedEvictionTargets,
                certifiedAdmissions = state.admittedFacilitators.value,
                currentOrdinal = state.key,
                penaltyDurationOrdinals = config.penaltyDurationOrdinals
              )
            // B2 readmissionCountdown maintenance (sticky-probation):
            //   1) Decrement any active probation counters by 1 -- but CLAMP at 0 instead of
            //      auto-clearing the entry. Earlier versions had `.filter(_._2 > 0)` here, which dropped
            //      the key when the countdown ran out. That made the AdmissionCertificate path
            //      semantically optional: a peer would auto-leave probation after N rounds
            //      regardless of whether quorum had ever witnessed its catch-up. Empirical
            //      consequence: ZERO admission certs assembled across 14 hours of alpha.50,
            //      because the StallDetector emission gate (probation intersect atTip-streak) only
            //      considers peers still in the probation set, but those peers exited probation
            //      via auto-clear before the streak threshold could fire.
            //   2) Seed entries for `justUnpenalized` (peers whose removalPenalty expired
            //      this round) at `readmissionProbationRounds`. These peers take the B2
            //      re-admission path, not the B1 deferral path.
            //   3) Clear entries for peers admitted via AdmissionCertificate this round
            //      (state.admittedFacilitators populated at buildSignatureTransition).
            //      This is the ONLY path that removes a peer from probation.
            // Order matters: decrement-then-clear-then-seed avoids decrementing a freshly
            // seeded entry in the same step. Admitted peers are removed last so an edge
            // case where the same peer is both admitted AND newly-unpenalized (shouldn't
            // happen but defended against) does not re-enter probation.
            val admittedThisRound = state.admittedFacilitators.value
            val finalReadmission = ReadmissionMaintenance.step(
              prev = state.lastOutcome.readmissionCountdown,
              justUnpenalized = justUnpenalized,
              admittedThisRound = admittedThisRound,
              probationRounds = config.readmissionProbationRounds
            )
            // Per-peer cumulative view-change-caused credits.
            //
            // For each view v in [0, state.viewNumber) the round attempted, recompute the
            // deterministic leader using the SAME inputs `selectLeaderWeighted` was called
            // with at round-start: state.lastOutcome.peerQuality, state.lastOutcome.peerSelfHealth,
            // state.lastOutcome.peerViewChanges, and a leaderPool derived from
            // state.coreFacilitators via the same graduation rule the creator applied.
            // Each resulting peer is credited with one view-change-caused. All inputs are
            // consensus-agreed (lastOutcome is signed, coreFacilitators is canonical at
            // round-start via CommitteeBuilder, entropy is derived from the prior snapshot hash,
            // config is deterministicConfigHash-gated), so every honest node computes the same
            // credit map byte-identically.
            //
            // v19: prior to multi-committee, this used `state.roundStartFacilitators` because
            // the leader pool was derived from the full round-start committee. In v19 the
            // creator restricts the leader pool to the Core committee, so the credit re-derivation
            // here switches to `state.coreFacilitators` for the same determinism contract.
            //
            // Determinism contract: the leaderPool re-derivation here MUST mirror the
            // creator's logic at GlobalSnapshotConsensusStateCreator. If the creator changes
            // the graduation rule, this credit logic MUST change in lockstep, or the
            // selectLeaderWeighted recomputation here will return a different peer than the
            // one the round actually elected at the same view -- producing a credit miss.
            val priorPeerQuality = state.lastOutcome.peerQuality
            val priorActive = state.coreFacilitators.value
            val priorGraduated = priorActive.filter { pid =>
              val (completed, participated) = priorPeerQuality.getOrElse(pid, (0, 0))
              participated >= config.minParticipationObservations && completed >= 1
            }
            val priorLeaderPool = if (priorGraduated.size >= 2) priorGraduated else priorActive
            val viewChangeCredits: SortedMap[PeerId, Long] =
              if (state.viewNumber <= 0 || priorLeaderPool.isEmpty) SortedMap.empty[PeerId, Long]
              else {
                val priorPeerQualityMap: Map[PeerId, (Int, Int)] = priorPeerQuality.toMap
                val priorPeerSelfHealthMap = state.lastOutcome.peerSelfHealth.toMap
                val priorPeerViewChangesMap = state.lastOutcome.peerViewChanges.toMap
                (0 until state.viewNumber).foldLeft(SortedMap.empty[PeerId, Long]) { (acc, v) =>
                  val failedLeader = facilitatorSelector.selectLeaderWeighted(
                    priorLeaderPool,
                    state.entropy,
                    viewNumber = v,
                    qualityScores = priorPeerQualityMap,
                    selfHealthHints = priorPeerSelfHealthMap,
                    peerViewChanges = priorPeerViewChangesMap,
                    minLeaderRatioPct = config.leaderRotationMinRatioPct,
                    hardLeaderQualityScorePct = config.hardLeaderQualityScorePct,
                    minLeaderPoolSize = config.minLeaderPoolSize
                  )
                  acc.updated(failedLeader, acc.getOrElse(failedLeader, 0L) + 1L)
                }
              }
            val accumulatedPeerViewChanges: SortedMap[PeerId, Long] = {
              val priorMap = state.lastOutcome.peerViewChanges
              val allKeys = (priorMap.keysIterator ++ viewChangeCredits.keysIterator).toSet
              SortedMap
                .from(allKeys.iterator.map { pid =>
                  pid -> (priorMap.getOrElse(pid, 0L) + viewChangeCredits.getOrElse(pid, 0L))
                })
                .filter { case (_, v) => v > 0L }
            }
            val nextOutcomeFacilitators = Facilitators(
              ConsensusPeerController.applyCertifiedAdmissions(
                state.roundStartFacilitators.value,
                state.admittedFacilitators.value
              )
            )
            val outcome = GlobalConsensusOutcome(
              state.key,
              // Canonical committee in the persisted outcome: the round-start committee plus
              // accepted AdmissionCertificate targets. This is not post-withdrawal
              // state.facilitators, and it does not include local candidate replay.
              nextOutcomeFacilitators,
              state.removedFacilitators,
              state.withdrawnFacilitators,
              state.eligibleFacilitators,
              Finished(f.signedMajorityArtifact, f.context, f.majorityTrigger, f.candidates, f.facilitatorsHash, f.snapshotHash),
              removalPenalties = finalPenalties,
              // v19 cleanup: inert -- no StateCreator consumer.
              deferralCountdown = SortedMap.empty[PeerId, Int],
              peerQuality = accumulatedQuality,
              cumulativeMissCounts = newCumulative,
              recentProofSizes = newRecentProofSizes,
              readmissionCountdown = finalReadmission,
              // v15: carry the accepted Proposal's `observedSelfHealth` forward as the next
              // round's leader-selection input. `state.observedSelfHealth` was populated via
              // REPLACE-on-accept at buildSignatureTransition from `leaderProposal.observedSelfHealth`.
              peerSelfHealth = state.observedSelfHealth.value,
              // v16: per-peer cumulative view-change-caused, deterministic from this round's
              // (entropy, viewNumber, lastOutcome, roundStartFacilitators) inputs above.
              peerViewChanges = accumulatedPeerViewChanges,
              // v22: rolling K-round signer-set window, repopulated. Drives the tier-demotion
              // hysteresis (TierTransitions.computeNextTiers above) and is carried forward as the
              // next round's window input. Fully sorted -> deterministic across the cluster.
              recentSigners = newRecentSigners,
              // v19: carried-forward multi-committee tier classification computed from this
              // round's signer participation (above).
              peerTiers = newPeerTiers,
              activeAdmissionScores = newActiveAdmissionScores,
              lastTimeoutCertificateVoters = state.acceptedTimeoutCertificateVoters,
              // v19 phase 2: view-from-time anchor for the next round's view derivation.
              recentRoundEndTimes = newRecentRoundEndTimes,
              // Controller evidence stages 1+3 (write-only). Option-wrap follows the
              // recentSigners-at-snapshot-boundary convention: None while empty so
              // pre-deploy encodings stay byte-stable under dropNullValues.
              controllerEvidence = if (newControllerEvidence.nonEmpty) Some(newControllerEvidence) else None,
              penaltyUntil = if (newPenaltyUntil.nonEmpty) Some(newPenaltyUntil) else None
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
              maybeFacilities <- maybeGetAllDeclarations(state, resources)(_.facility)
              facilitators = maybeFacilities.map(_.keys.toList).getOrElse(List.empty[PeerId])
              _ <- loggerBundle.consensus.collectingFacilities(facilitators)
              // NOTE: facilitatorsHash fork check is handled by identifyForkedPeers below (evicts minority
              // instead of killing this node). Do NOT call checkForkByFacilitatorsHash here — after stall-based
              // eviction, different nodes may legitimately have different facilitator sets, which would cause
              // cascading false-positive fork detections and kill all nodes.
              _ <- maybeFacilities.traverse_(
                checkForkByLastSnapshotHash(_, status.lastSnapshotHash, config.forkConfirmationMinObservations)
              )
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

              _ <- cleanFacilities.traverse_ { _ =>
                ConsensusLog.debug(
                  logger,
                  Category.Fork,
                  state.key.show,
                  "n/a",
                  Event.ForkChecksPassed,
                  "facilitatorsHash" -> status.facilitatorsHash.show.take(8),
                  "lastSnapshotHash" -> status.lastSnapshotHash.show.take(8)
                )
              }

              result <- cleanFacilities.flatTraverse { facilities =>
                // Only fork-evicted peers (those who declared a Facility with a divergent
                // `facilitatorsHash`) accumulate into `state.removedFacilitators`. That set is
                // consensus-agreed because every non-forked node sees the same declarations and
                // computes the same minority via `identifyForkedPeers`.
                //
                // Peers simply missing from the local facility map (didn't declare in time to
                // hit quorum on this node) are NOT evicted: that set is local-observation-
                // dependent (a race between gossip arrival and the quorum threshold) and
                // evicting based on it creates divergent `removedFacilitators` across nodes,
                // which cascades into divergent `penalizedThisRound`, committee selection,
                // and ultimately divergent signed outcomes. See ord-5-to-6 divergence
                // analysis: fast finalizers ejected slow declarers differently than nodes
                // that waited a few more milliseconds.
                //
                // Missing-declaration peers simply don't participate this round. They remain
                // in `state.facilitators` for future rounds; stall-cycle abandonment + VCC
                // view change handle liveness if they're persistently unresponsive.
                val forkEvictedPeers: Set[PeerId] = maybeFacilities match {
                  case Some(orig) => orig.keySet -- facilities.keySet
                  case None       => Set.empty
                }
                val updatedState: GlobalSnapshotConsensusState =
                  if (forkEvictedPeers.nonEmpty)
                    state.copy[GlobalSnapshotKey, GlobalSnapshotStatus, GlobalConsensusOutcome, GlobalConsensusKind](
                      facilitators = Facilitators(state.facilitators.value.filter(pid => !forkEvictedPeers.contains(pid))),
                      removedFacilitators = RemovedFacilitators(state.removedFacilitators.value ++ forkEvictedPeers)
                    )
                  else state
                maybeWaitForAdmissionCertificates(updatedState, resources, facilities).flatMap { waitForAcs =>
                  if (waitForAcs)
                    ConsensusLog
                      .info(
                        logger,
                        Category.Phase,
                        state.key.show,
                        "n/a",
                        Event.Admission,
                        "stage" -> "pre_proposal_grace",
                        "active" -> updatedState.roundStartFacilitators.value.size.toString,
                        "target" -> activeAdmissionTarget(updatedState).toString,
                        "candidates" -> openAdmissionCandidates(updatedState, facilities).size.toString,
                        "admissionVoteTargets" -> resources.admissionVotes.size.toString
                      )
                      .as(none[Transition])
                  else toProposalsPhase(updatedState, facilities)
                }
              }
            } yield result
          }
        }

      private val AdmissionPreProposalGrace: FiniteDuration = 1500.millis

      private def activeAdmissionTarget(state: GlobalSnapshotConsensusState): Int =
        ActiveFacilitatorAdmission.activeAdmissionTarget(
          config.activeFacilitatorTarget,
          config.coreCommitteeSize,
          state.coreFacilitators.value.size
        )

      private def openAdmissionCandidates(
        state: GlobalSnapshotConsensusState,
        facilities: SortedMap[PeerId, Facility]
      ): Set[PeerId] = {
        val committee = state.roundStartFacilitators.value.toSet
        facilities.values.flatMap(_.candidates.value).filterNot(committee.contains).toSet
      }

      private def maybeWaitForAdmissionCertificates(
        state: GlobalSnapshotConsensusState,
        resources: ConsensusResources[GlobalSnapshotArtifact, GlobalConsensusKind],
        facilities: SortedMap[PeerId, Facility]
      ): F[Boolean] =
        for {
          now <- Async[F].monotonic
          acs <- consensusStorage.getAssembledAdmissionCertificates(state.key)
          target = activeAdmissionTarget(state)
          activeBelowTarget = state.roundStartFacilitators.value.size < target
          hasAdmissionEvidence = openAdmissionCandidates(state, facilities).nonEmpty || resources.admissionVotes.nonEmpty
          graceOpen = now - state.createdAt < AdmissionPreProposalGrace
        } yield
          activeBelowTarget &&
            config.activeAdmissionMaxExpansionPerRound > 0 &&
            hasAdmissionEvidence &&
            acs.isEmpty &&
            graceOpen

      /** Caps the assembled admission certificates attached to an outgoing proposal at the validation limit (`acs_too_many` in
        * `validateProposalAcs`). Selection is delegated to the shared `AdmissionCertificateSelector` -- see its scaladoc for the
        * determinism + wedge rationale. Logs + counts only when the cap actually drops certificates. Mirrored verbatim in
        * `CurrencySnapshotConsensusStateAdvancer.capAssembledAdmissionCertificates` (keep in sync).
        */
      private def capAssembledAdmissionCertificates(
        key: GlobalSnapshotKey,
        assembled: Set[AdmissionCertificate]
      ): F[List[AdmissionCertificate]] = {
        val selection = AdmissionCertificateSelector.select(assembled, config.activeAdmissionMaxExpansionPerRound)
        ConsensusLog
          .info(
            logger,
            Category.Phase,
            key.show,
            "Leader",
            Event.Admission,
            "stage" -> "proposal_cap",
            "kept" -> selection.kept.map(c => ConsensusLog.pid(c.targetPeer)).mkString(","),
            "dropped" -> selection.dropped.map(c => ConsensusLog.pid(c.targetPeer)).mkString(",")
          )
          .productR(Metrics[F].incrementCounter("dag_consensus_admission_cert_capped_total"))
          .whenA(selection.dropped.nonEmpty)
          .as(selection.kept)
      }

      private def toProposalsPhase(
        state: GlobalSnapshotConsensusState,
        facilities: SortedMap[PeerId, Facility]
      ): F[Option[Transition]] = {
        val (candidates, triggers) = facilities.foldMap(f => (f.candidates.value, f.trigger.toList))

        // Compute hash UNION - include events ANY facilitator has, then sync missing
        val allHashSets = facilities.values.map(_.eventHashes).toList
        val unionHashes = allHashSets.reduceOption(_ union _).getOrElse(Set.empty[Hash])

        val trigger = pickMajority(triggers).getOrElse(EventTrigger)

        // v7: leader's positive observation of which round-start facilitators sent a Facility
        // declaration this round. Includes self because the leader's own Facility is implicit
        // (`maybeGetAllDeclarations` returned the cleaned post-fork-eviction set, which excludes
        // self by convention; self is always a responder for its own proposal). Sorted at
        // construction for deterministic proposal-hash agreement (mirrors evictionCertificates
        // / admissionCertificates ordering pattern). Bootstrap gate: emit empty during
        // isInBootstrap so leader-build aligns with validation gate (codex turn 2 fix #1) —
        // peerQuality update site falls back to today's "non-evicted = completed" semantic
        // during bootstrap.
        val observedResponders: List[PeerId] =
          if (isInBootstrap(state)) List.empty
          else (facilities.keySet + selfId).toList.sorted
        // v15: aggregate each facilitator's self-reported `selfHealthHint` into a single
        // consensus-agreed map. Bootstrap path emits empty so the proposal-build path mirrors
        // observedResponders' bootstrap gate; absence -> default Healthy at read time means a
        // freshly-restarted cluster picks leaders without hints until the first hint-bearing
        // round closes.
        val observedSelfHealth: SortedMap[PeerId, SelfHealthHint] =
          if (isInBootstrap(state)) SortedMap.empty[PeerId, SelfHealthHint]
          else SortedMap.from(facilities.iterator.flatMap { case (pid, f) => f.selfHealthHint.map(pid -> _) })
        // Surface participation visibility for stall-prevention dashboards: leader's
        // observed responder count + ratio against the canonical committee size at this
        // round. Low ratios indicate Facility-phase delivery issues even when consensus
        // ultimately finalizes.
        val committeeSize = state.roundStartFacilitators.value.size
        val responderRatio: Double =
          if (committeeSize > 0) observedResponders.size.toDouble / committeeSize.toDouble else 0.0

        // Build map of hash -> all peers who have it (try in order until one responds)
        val hashToPeers: Map[Hash, List[PeerId]] = facilities.toList.flatMap {
          case (peerId, facility) => facility.eventHashes.map(_ -> peerId)
        }.groupMap(_._1)(_._2)

        // v19 phase 2: compute the round's `consensusEndTime` from the accepted Facility
        // set. Median + parent-clamp absorbs proposer-clock outliers; below-threshold
        // facility counts produce `None` so the consume site falls back to phase 1 view
        // derivation. The accepted Facility set is post-fork-eviction (clean facilities)
        // so every honest node converges on the same value. Stashed onto state below
        // and read at outcome-finalize for `recentRoundEndTimes`.
        // SortedMap's `lastOption` returns the entry with the highest key (most recent
        // ordinal); the corresponding `endTime` is the parent round's anchor used for
        // the Bitcoin MTP-style clamp inside `compute`.
        val parentEndTime: Option[Long] = state.lastOutcome.recentRoundEndTimes.lastOption.map(_._2)
        val outcomeEndTime: Option[Long] = ConsensusEndTime.compute(facilities.values, parentEndTime)
        // Type-arg-elaborated copy mirrors the pattern at the forkEviction site above:
        // bare `state.copy(...)` would default the Kind type parameter to Nothing on
        // anonymous classes when the original was inferred from the abstract type.
        val stateWithEndTime: GlobalSnapshotConsensusState =
          state.copy[GlobalSnapshotKey, GlobalSnapshotStatus, GlobalConsensusOutcome, GlobalConsensusKind](
            outcomeEndTime = outcomeEndTime
          )

        for {
          _ <- maintainDagAwaitingParentQueue(state.lastOutcome.finished.context)

          // Get local hashes and identify what we're missing
          localHashes <- eventMempool.getEventHashes
          missingHashes = unionHashes -- localHashes

          _ <- logger.debug(
            s"[HashUnion] Ordinal=${state.key.value} facilitators=${facilities.size} " +
              s"unionHashes=${unionHashes.size} localHashes=${localHashes.size} missing=${missingHashes.size}"
          )

          // Sync missing events from peers before building proposal
          _ <- syncMissingEvents(missingHashes, hashToPeers).whenA(missingHashes.nonEmpty)
          // A peer may have declared a permanently-dead hash in its Facility before reaping its own
          // active mempool. Drop anything re-fetched from those stale declarations before proposal build.
          _ <- DagAwaitingParentQueue.evictPermanentlyRejected(eventMempool, state.lastOutcome.finished.context, logger).void

          _ <- Metrics[F].updateGauge("dag_consensus_observed_responders_count", observedResponders.size.toLong)
          _ <- Metrics[F].updateGauge("dag_consensus_facility_quorum_ratio", responderRatio)

          result <- buildProposalTransition(stateWithEndTime, unionHashes, candidates, trigger, observedResponders, observedSelfHealth)
        } yield result
      }

      /** Sync missing events from peers who have them.
        *
        * Each hash may be fetchable from multiple peers. For each hash, the peers list is tried in order until one succeeds, providing
        * fallback when the first peer is offline.
        */
      private def syncMissingEvents(
        missingHashes: Set[Hash],
        hashToPeers: Map[Hash, List[PeerId]]
      ): F[Unit] = {
        // Group by first available peer but keep the full candidate list per hash for fallback
        val hashesWithPeers: List[(Set[Hash], List[PeerId])] = missingHashes.toList
          .flatMap(h => hashToPeers.get(h).map(peers => (h, peers)))
          .groupMap(_._2)(_._1)
          .toList
          .map { case (peers, hashes) => (hashes.toSet, peers) }

        for {
          _ <- logger.info(s"[EventSync] Syncing ${missingHashes.size} missing events from ${hashesWithPeers.size} peer groups")
          _ <- hashesWithPeers.parTraverse_ {
            case (hashes, peers) => fetchEventsFromPeers(hashes, peers)
          }
          _ <- logger.debug(s"[EventSync] Sync complete")
        } yield ()
      }

      /** Fetch specific events, trying peers in order until one responds. */
      private def fetchEventsFromPeers(hashes: Set[Hash], peers: List[PeerId]): F[Unit] =
        peers match {
          case Nil =>
            if (hashes.nonEmpty)
              logger.warn(s"[EventSync] No more peers for ${hashes.size} remaining hashes, dropping")
            else Applicative[F].unit
          case peerId :: rest =>
            clusterStorage.getPeer(peerId).flatMap {
              case None => fetchEventsFromPeers(hashes, rest)
              case Some(peer) =>
                eventGossipClient
                  .requestEvents(IWantRequest(hashes))
                  .run(Peer.toP2PContext(peer))
                  .flatMap { response =>
                    val receivedHashes = response.events.map(_._1).toSet
                    val stillMissing = hashes -- receivedHashes
                    response.events.traverse_ {
                      case (_, signedEvent) => eventMempool.add(signedEvent).void
                    } >>
                      (if (stillMissing.nonEmpty)
                         logger.debug(
                           s"[EventSync] Peer ${peerId.show.take(8)} returned ${receivedHashes.size}/${hashes.size}, retrying ${stillMissing.size} with next peer"
                         ) >>
                           fetchEventsFromPeers(stillMissing, rest)
                       else Applicative[F].unit)
                  }
                  .handleErrorWith { err =>
                    logger.warn(s"[EventSync] Peer ${peerId.show.take(8)} failed: ${err.getMessage}, trying next") >>
                      fetchEventsFromPeers(hashes, rest)
                  }
            }
        }

      private val dagAwaitingParentConfig = DagAwaitingParentConfig.default
      private val dagAwaitingParentOutcomeLabel = Metrics.unsafeLabelName("outcome")
      private val maxAwaitingParentReactivationPerRound = 128

      private def maintainDagAwaitingParentQueue(context: GlobalSnapshotContext): F[Unit] =
        HasherSelector[F].withCurrent { implicit hasher =>
          DagAwaitingParentQueue
            .maintain(
              eventMempool,
              context,
              dagAwaitingParentConfig,
              maxAwaitingParentReactivationPerRound,
              logger
            )
            .void
        } >>
          // Reap permanently-dead DAG blocks (conflicting tx already committed in a prior snapshot) from the
          // active mempool so they stop being re-rejected every proposal build. Judged against the committed
          // context only, so it cannot evict a block that could still become valid.
          DagAwaitingParentQueue.evictPermanentlyRejected(eventMempool, context, logger).void

      private def buildProposalTransition(
        state: GlobalSnapshotConsensusState,
        commonHashes: Set[Hash],
        candidates: Set[PeerId],
        majorityTrigger: ConsensusTrigger,
        observedResponders: List[PeerId],
        observedSelfHealth: SortedMap[PeerId, SelfHealthHint]
      ): F[Option[Transition]] =
        for {
          _ <- clearTimeTriggerIfNeeded(majorityTrigger)
          facilitatorsHash <- hashFacilitators(state)

          // Pull events from mempool using hash union
          mempoolData <- eventMempool.getMultiple(commonHashes).map { hashToHashed =>
            val events = hashToHashed.values.map(_.signed.value).toSet
            val hashToEvent = hashToHashed.map { case (h, hashed) => h -> hashed.signed.value }
            (events, hashToEvent)
          }
          (mempoolEvents, mempoolHashToEvent) = mempoolData

          // Restore any previous savepoint from an abandoned round at the SAME ordinal FIRST, so the
          // content-guarded sync below runs on (and verifies) the FINAL pre-proposal state. Restoring AFTER
          // the sync would replace a forced resync with the abandoned round's stale producer state.
          previousSp <- proposalSavepointRef.getAndSet(none)
          _ <- previousSp.traverse_ {
            case (spKey, sp) =>
              if (spKey === state.key)
                sp.restore >>
                  ConsensusLog.info(
                    logger,
                    Category.Lifecycle,
                    state.key.show,
                    "n/a",
                    Event.MptSavepointRestored,
                    "savepointKey" -> spKey.show,
                    "currentKey" -> state.key.show
                  ) >>
                  Metrics[F].incrementCounter("dag_consensus_mpt_savepoint_restored_total")
              else
                ConsensusLog.warn(
                  logger,
                  Category.Lifecycle,
                  state.key.show,
                  "n/a",
                  Event.MptSavepointDiscardedWrongKey,
                  "savepointKey" -> spKey.show,
                  "currentKey" -> state.key.show
                )
          }

          // After recovery download (or a just-restored savepoint), the MPT may lag or mismatch the lastOutcome
          // (e.g. downloaded to ordinal N but the outcome was fetched at N+1, or the restore re-applied a stale
          // pre-mutation state). Ensure the MPT reflects the lastOutcome's state before computing the proposal.
          // syncFullIfNeeded is a no-op only when already synced AND the producer's current root reproduces the
          // lastOutcome's signed stateProof root; on mismatch it forces a full resync so an abandoned-round
          // mutation can never leave the MPT stale under createArtifact().
          _ <- mptStore.syncFullIfNeeded[Json](
            HasherSelector[F].withCurrent(implicit hs => state.lastOutcome.finished.context.allStateEntries[F]),
            state.lastOutcome.key,
            state.lastOutcome.finished.signedMajorityArtifact.value.stateProof.mptRoot
          )
          // Take a fresh savepoint before mutations
          sp <- mptStore.savepoint
          _ <- proposalSavepointRef.set((state.key, sp).some)

          (artifact, context, returnedEvents) <- createArtifact(state, majorityTrigger, mempoolEvents)

          // Do not remove accepted events at proposal time. A proposal can lose the round, or
          // different facilitators can propose the same event at adjacent ordinals. Events are
          // removed only after the winning artifact is finalized and persisted.
          heldDagHashes = {
            val returnedSet = returnedEvents.toSet
            mempoolHashToEvent.collect {
              case (hash, event @ DAGEvent(_)) if returnedSet.contains(event) => hash
            }.toSet
          }
          _ <- eventMempool.suspend(heldDagHashes).whenA(heldDagHashes.nonEmpty)
          _ <- Metrics[F]
            .incrementCounterBy(
              "dag_global_snapshot_dag_tx_awaiting_parent_total",
              heldDagHashes.size.toLong,
              Seq(dagAwaitingParentOutcomeLabel -> "held")
            )
            .whenA(heldDagHashes.nonEmpty)
          hash <- hashArtifact(artifact)
          _ <- checkFollowerExit(state)
          isLeader = selfId === state.leader
          role = if (isLeader) "LEADER" else "FOLLOWER"
          withdrawnCount = state.withdrawnFacilitators.value.size
          _ <- ConsensusLog.info(
            logger,
            Category.Phase,
            state.key.show,
            role,
            Event.FacilitiesToProposals,
            (Seq(
              "ordinal" -> artifact.ordinal.show,
              "trigger" -> majorityTrigger.toString,
              "hash" -> hash.show.take(8),
              "facilitators" -> state.facilitators.value.size.toString,
              "candidates" -> candidates.size.toString,
              "leader" -> ConsensusLog.pid(state.leader),
              "self" -> ConsensusLog.pid(selfId),
              "view" -> state.viewNumber.toString,
              "facilitatorsHash" -> facilitatorsHash.show.take(8),
              "lastSnapshotHash" -> state.lastOutcome.finished.snapshotHash.show.take(8),
              // Alpha.94: parent + current committee diagnostics. When two peers transition into
              // CollectingProposals for the same ordinal but compute different `facilitatorsHash`
              // values, we can grep across log files for (ordinal, parentKey) and see whether the
              // disagreement is on the CURRENT round's committee composition (different
              // eligibleFacilitators -> different sets -> different hashes) or on the PARENT
              // state (different lastOutcome -> different starting point). The earlier
              // `facilitator_set_mismatch_revalidate` log conflated these two and produced
              // misleading wedge signals; see `project_alpha92_wedge_may21.md`. Costs one extra
              // log line per round transition.
              "parentFacilitatorsHash" -> state.lastOutcome.finished.facilitatorsHash.show.take(8),
              "parentKey" -> state.lastOutcome.key.show,
              "roundStartFacilitators" -> state.roundStartFacilitators.value.size.toString,
              "coreFacilitators" -> state.coreFacilitators.value.size.toString,
              "tier1Facilitators" -> state.tier1Facilitators.value.size.toString
            ) ++ (if (withdrawnCount > 0) Seq("withdrawn" -> withdrawnCount.toString) else Seq.empty)): _*
          )
          _ <- ConsensusLog.debug(
            logger,
            Category.Proposal,
            state.key.show,
            role,
            Event.ProposalStateProof,
            "detail" -> describeStateProof(artifact.stateProof)
          )
          _ <- ConsensusLog.debug(
            logger,
            Category.Proposal,
            state.key.show,
            role,
            Event.ProposalContextDigest,
            "detail" -> contextDigest(context)
          )

          // Leader-side vote-lock safety: if we are locked on a prior QC hash and about to propose
          // a different hash, abort and let the next view handle it. This prevents the leader from
          // proposing a new hash while a previous-view proposal commitment still stands.
          leaderLock <- consensusStorage.getVoteLock(state.key)
          // Stale-VCC suppression: only fetch when the round has advanced past the seed view.
          // `clearResourcesPreservingDeclarations` preserves `assembledVccR` across retries; without
          // gating the fetch, a seed view > 0 round could embed/consult a stale cert from a prior
          // attempt. The alpha.90 P0 #1 self-wedge fix stamps `initialViewNumber` on the state at
          // creation; this gate uses that stamp instead of bare `viewNumber > 0`.
          maybeAssembledVccRaw <-
            if (state.viewNumber > state.initialViewNumber) consensusStorage.getAssembledVcc(state.key)
            else none[ViewChangeCertificate].pure[F]
          maybeTimeoutCertificate <-
            if (state.viewNumber > state.initialViewNumber)
              consensusStorage
                .getResources(state.key)
                .map(_.timeoutCertificates.get((state.viewNumber.toLong - 1L, state.viewNumber.toLong)))
            else none[TimeoutCertificate].pure[F]
          maybeAssembledVcc = maybeTimeoutCertificate.fold {
            maybeAssembledVccRaw.filter(vcc => vcc.fromView === (state.viewNumber.toLong - 1L) && vcc.toView === state.viewNumber.toLong)
          }(_ => none[ViewChangeCertificate])
          vccHighestQc = maybeAssembledVcc.flatMap(_.highestQcInVcc)
          tcHighestQc = maybeTimeoutCertificate.flatMap { tc =>
            val qcs = tc.votes.toNonEmptyList.toList.flatMap(_.value.highestKnownQc)
            qcs.groupBy(_.view).toList.sortBy(_._1).lastOption.flatMap {
              case (_, atView) =>
                val hashes = atView.map(_.proposalHash).toSet
                if (hashes.size === 1) atView.headOption else None
            }
          }
          // Highest-QC carry-forward: if the VCC carries a QC, the leader MUST propose that hash.
          // If our locally-built artifact hash differs, abort and let the next view retry. Mirror
          // the fetch gate above -- only enforce when post-seed.
          vccMismatch = isLeader && state.viewNumber > state.initialViewNumber && vccHighestQc.exists(_.proposalHash =!= hash)
          tcMismatch = isLeader && state.viewNumber > state.initialViewNumber && tcHighestQc.exists(_.proposalHash =!= hash)
          // Missing VCC at view > 0 is normally a race (VCC was cleared between assembly and
          // proposal build) -- but if Core has degenerated to a single peer there is no quorum
          // to assemble from, so no VCC is achievable. Suppress the abort in that case: the
          // solo leader proposes without a VCC. With Core=1 there are no other validators to
          // reject the no-VCC proposal; the proposal validates locally and the snapshot
          // finalizes solo. Without this bypass, alpha.88 wedged whenever a node entered
          // a momentary solo state after self-rejoin or community-peer drop-off (overnight
          // alpha.88 monitor saw repeated `ROUND_STARTED facs=1 leader=e2f4496e view=0` with
          // vcc_missing_for_view_gt_0 on every retry).
          isSoloCore = state.coreFacilitators.value.size <= 1
          // A round-start seed view is accepted without consulting a locally cached VCC. Once
          // `viewNumber > initialViewNumber`, a real view-change has occurred and the VCC
          // requirement re-engages.
          isRoundStartView = state.viewNumber === state.initialViewNumber
          viewCertMissing =
            isLeader && state.viewNumber > 0 && maybeAssembledVcc.isEmpty && maybeTimeoutCertificate.isEmpty && !isSoloCore && !isRoundStartView
          aborted = (isLeader && leaderLock
            .flatMap(_.lockedQc)
            .exists(_.proposalHash =!= hash)) || vccMismatch || tcMismatch || viewCertMissing
          _ <- ConsensusLog
            .warn(
              logger,
              Category.Validation,
              state.key.show,
              role,
              Event.WithdrawValidationFail,
              "reason" -> "leader_locked_on_different_qc",
              "lockedQcHash" -> leaderLock.flatMap(_.lockedQc).map(_.proposalHash.show.take(8)).getOrElse("none"),
              "proposingHash" -> hash.show.take(8)
            )
            .whenA(isLeader && leaderLock.flatMap(_.lockedQc).exists(_.proposalHash =!= hash))
          _ <- ConsensusLog
            .warn(
              logger,
              Category.Validation,
              state.key.show,
              role,
              Event.WithdrawValidationFail,
              "reason" -> "vcc_highest_qc_mismatch",
              "qcHash" -> vccHighestQc.map(_.proposalHash.show.take(8)).getOrElse("none"),
              "proposingHash" -> hash.show.take(8),
              "view" -> state.viewNumber.toString
            )
            .whenA(vccMismatch)
          _ <- ConsensusLog
            .warn(
              logger,
              Category.Validation,
              state.key.show,
              role,
              Event.WithdrawValidationFail,
              "reason" -> "tc_highest_qc_mismatch",
              "qcHash" -> tcHighestQc.map(_.proposalHash.show.take(8)).getOrElse("none"),
              "proposingHash" -> hash.show.take(8),
              "view" -> state.viewNumber.toString
            )
            .whenA(tcMismatch)
          _ <- ConsensusLog
            .warn(
              logger,
              Category.Validation,
              state.key.show,
              role,
              Event.WithdrawValidationFail,
              "reason" -> "view_cert_missing_for_view_gt_0",
              "view" -> state.viewNumber.toString
            )
            .whenA(viewCertMissing)
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
                  state.lastOutcome.finished.snapshotHash,
                  observedResponders,
                  observedSelfHealth
                )
              ),
              sideEffect =
                if (isLeader)
                  (for {
                    ecs <-
                      if (isInBootstrap(state)) Set.empty[EvictionCertificate].pure[F]
                      else consensusStorage.getAssembledEvictionCertificates(state.key)
                    acs <- consensusStorage
                      .getAssembledAdmissionCertificates(state.key)
                      .flatMap(capAssembledAdmissionCertificates(state.key, _))
                  } yield (ecs, acs)).flatMap {
                    case (ecs, acs) =>
                      spreadProposal(
                        state,
                        state.key,
                        hash,
                        facilitatorsHash,
                        artifact,
                        state.lastOutcome.finished.snapshotHash,
                        state.viewNumber.toLong,
                        maybeAssembledVcc,
                        maybeTimeoutCertificate,
                        ecs.toList,
                        acs,
                        observedResponders,
                        observedSelfHealth
                      )
                  }
                else
                  Applicative[F].unit
            ).some

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
        * '''MptStore safety''': The slow path takes an MptStore savepoint before validation and restores it on failure. This prevents
        * partial state from cascading to future rounds.
        */
      private def advanceFromProposals(
        state: GlobalSnapshotConsensusState,
        status: CollectingProposals,
        resources: ConsensusResources[GlobalSnapshotArtifact, GlobalConsensusKind]
      ): F[Option[Transition]] =
        loggerBundle.app.withOrdinal(status.proposalArtifactInfo.artifact.ordinal) {
          HasherSelector[F].withCurrent { implicit hasher =>
            // Guard: if we already withdrew from this round-and-view, don't re-enter validation.
            // Without this, a validation failure (which returns none[Transition]) causes a hot loop:
            // the leader's proposal stays in resources, so every checkUpdate re-enters here,
            // re-validates, re-fails, and re-withdraws (7+/sec observed in production).
            //
            // Read only from state.withdrawnFacilitators (view-scoped, cleared by the VCC-driven
            // view-change reset in StateTransitions). Previously this OR-merged with
            // resources.withdrawalsMap.get(selfId), which is round-scoped and never cleared across
            // views — a view-0 withdrawal would keep this predicate true forever, wedging the node
            // permanently (observed gl0-4 ord-3 stuck for 11 min).
            val alreadyWithdrawn = state.withdrawnFacilitators.value.contains(selfId)

            if (alreadyWithdrawn)
              none[Transition].pure[F]
            else {
              val leader = state.leader
              val maybeLeaderProposal = resources.peerDeclarationsMap.get(leader).flatMap(_.proposal)

              maybeLeaderProposal match {
                case Some(leaderProposal) =>
                  for {
                    _ <- loggerBundle.consensus.collectingProposals(List(leader))
                    // Skip facilitatorsHash fork check when view > 0 (eviction happened), when
                    // transitioning from solo genesis to multi-node consensus (penalty state diverges),
                    // or during joining grace period (peer quality scores haven't converged yet).
                    lastSolo <- wasLastRoundSolo
                    inGrace <- nodeStorage.isInJoiningGracePeriod
                    // Single-source authoritative comparison: leader-vs-self. minObservations=1 because the
                    // proposal IS the reference, not a polled majority sample.
                    _ <- checkForkByFacilitatorsHash(
                      SortedMap(leader -> leaderProposal),
                      status.facilitatorsHash,
                      minObservations = 1
                    )(_.facilitatorsHash).whenA(!lastSolo && !inGrace)
                    _ <- checkForkByLastSnapshotHash(
                      SortedMap(leader -> leaderProposal),
                      status.lastSnapshotHash,
                      minObservations = 1
                    )
                    result <- resolveLeaderProposal(state, status, resources, leaderProposal)
                  } yield result
                case None =>
                  if (selfId === state.leader)
                    // Leader (possibly after view change) — spread proposal so peers can advance.
                    // Include any assembled VCC for view > 0 so followers accept the re-spread.
                    // Include any assembled EvictionCertificates so persistently-absent peers
                    // get evicted at proposal acceptance (Phase B1). Symmetrically, include any
                    // assembled AdmissionCertificates so probation peers ready at tip get
                    // re-admitted (Phase B2).
                    (for {
                      maybeVccRaw <-
                        if (state.viewNumber > state.initialViewNumber) consensusStorage.getAssembledVcc(state.key)
                        else none[ViewChangeCertificate].pure[F]
                      maybeTc <-
                        if (state.viewNumber > state.initialViewNumber)
                          consensusStorage
                            .getResources(state.key)
                            .map(_.timeoutCertificates.get((state.viewNumber.toLong - 1L, state.viewNumber.toLong)))
                        else none[TimeoutCertificate].pure[F]
                      maybeVcc = maybeTc.fold {
                        maybeVccRaw.filter(vcc => vcc.fromView === (state.viewNumber.toLong - 1L) && vcc.toView === state.viewNumber.toLong)
                      }(_ => none[ViewChangeCertificate])
                      ecs <-
                        if (isInBootstrap(state)) Set.empty[EvictionCertificate].pure[F]
                        else consensusStorage.getAssembledEvictionCertificates(state.key)
                      acs <- consensusStorage
                        .getAssembledAdmissionCertificates(state.key)
                        .flatMap(capAssembledAdmissionCertificates(state.key, _))
                    } yield (maybeVcc, maybeTc, ecs, acs)).flatMap {
                      case (maybeVcc, maybeTc, ecs, acs) =>
                        ConsensusLog.info(
                          logger,
                          Category.Phase,
                          state.key.show,
                          "Leader",
                          Event.ProposalRespread,
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
                            status.lastSnapshotHash,
                            state.viewNumber.toLong,
                            maybeVcc,
                            maybeTc,
                            ecs.toList,
                            acs,
                            // v7 codex turn 2 fix #2: re-spread MUST read observedResponders
                            // from the immutable status, not recompute. Otherwise honest re-spread
                            // could emit a different set than the original first-spread.
                            // v15: same rationale for observedSelfHealth.
                            status.observedResponders,
                            status.observedSelfHealth
                          ).as(none[Transition])
                    }
                  else
                    none[Transition].pure[F]
              }
            }
          }
        }

      /** Alpha.97 same-key soft-reset outcome. The boolean shape was insufficient because callers must react DIFFERENTLY to the two
        * suppression cases:
        *   - `BudgetExhausted` -- the wedge is unrecoverable in place at this key, caller should escalate to heavy Download recovery.
        *   - `NoReadyPeerWithUsefulDeclarations` -- the cluster lacks a proven bootstrap source RIGHT NOW. Forcing this peer out of Ready
        *     here would just feed the recovery cascade (a Core peer leaves Ready precisely when the network lacks a recovery source).
        *     Caller should log + fall through to NORMAL stall handling (the existing StallDetector / AbandonmentTracker path will
        *     eventually fire if the situation persists).
        */
      private sealed trait SoftResetOutcome
      private object SoftResetOutcome {
        case object Fired extends SoftResetOutcome
        case object SuppressedBudgetExhausted extends SoftResetOutcome
        case object SuppressedNoReadyPeerWithUsefulDeclarations extends SoftResetOutcome
      }

      /** Alpha.97 same-key soft-reset attempt. See `SoftResetOutcome` for the per-outcome action contract. The helper logs each outcome
        * with `category` + `triggerCount` + `softResetCount` and increments the appropriate Prometheus counter.
        *
        * On `Fired`: clears the volatile round state via `consensusStorage.softResetRoundState` (state, artifacts, VCC, vote locks,
        * eviction/admission cert slots; peer declarations preserved as the bootstrap source for the rebuild), increments the soft-reset
        * budget counter, clears the stale-local-view rejection counter.
        *
        * NOTE on latency (codex follow-up, alpha.98 candidate): the cleared state is re-created on the next normal time-trigger (~22s at
        * default cadence) when the FSM next polls. We do not currently queue an immediate restart because the advancer does not hold a
        * handle to the consensus command queue (it is wired AFTER the advancer in `GlobalSnapshotConsensus.make`). Adding that handle
        * threads `Queue[F, ConsensusCommand[...]]` through the advancer factory and is deferred to keep this patch surgical.
        *
        * Category labels distinguish the two call sites in Prometheus + structured logs: `stale_local_view` (VCC-validation rejections from
        * `logVccReject`) and `artifact_mismatch` (consecutive_validation_failures from the artifact-hash mismatch path below).
        */
      private def trySoftResetAtSameKey(
        key: GlobalSnapshotKey,
        category: String,
        triggerCount: Int,
        role: String
      ): F[SoftResetOutcome] = {
        // The bootstrap source must be Ready (not WaitingForReady / WFD / etc.), at
        // the same or higher key (so they have a current-or-ahead view of the round),
        // with a non-empty facility or proposal we can read locally. The peer-current-
        // keys map is the same source AbandonmentTracker uses for its `peersAtHigherKey`
        // check.
        def gateAllowsReset: F[Boolean] =
          for {
            responsivePeers <- clusterStorage.getResponsivePeers
            readyIds = responsivePeers.filter(_.state === NodeState.Ready).map(_.id).toSet
            peerKeys <- consensusStorage.getPeerCurrentKeys
            decls <- consensusStorage.getPeerDeclarations(key)
          } yield
            decls.exists {
              case (peerId, d) =>
                // Explicitly exclude self: self's Facility is locally self-stored at round
                // start (see GlobalSnapshotConsensusStateCreator) and would otherwise let
                // us "bootstrap" from our own (wedged) view. The reset must rebuild from
                // external cluster evidence only. `getResponsivePeers` likely excludes
                // self today, but this is recovery code -- the safety condition should
                // not depend on indirect behavior of other components.
                peerId =!= selfId &&
                readyIds.contains(peerId) &&
                peerKeys.get(peerId).exists(_ >= key) &&
                (d.facility.nonEmpty || d.proposal.nonEmpty)
            }

        consensusStorage.getSoftResetCountAtSameKey(key).flatMap { softResetCount =>
          val budgetExhausted = softResetCount >= consensusConfig.maxSoftResetsAtSameKey
          if (budgetExhausted)
            ConsensusLog.warn(
              logger,
              Category.Recovery,
              key.show,
              role,
              Event.SoftResetSuppressed,
              "category" -> category,
              "triggerCount" -> triggerCount.toString,
              "softResetCount" -> softResetCount.toString,
              "maxSoftResetsAtSameKey" -> consensusConfig.maxSoftResetsAtSameKey.toString,
              "reason" -> "budget_exhausted"
            ) >>
              Metrics[F].incrementCounter(
                "dag_consensus_soft_reset_suppressed_total",
                Seq(Metrics.unsafeLabelName("reason") -> "budget_exhausted")
              ) >>
              (SoftResetOutcome.SuppressedBudgetExhausted: SoftResetOutcome).pure[F]
          else
            gateAllowsReset.flatMap { allowed =>
              if (!allowed)
                ConsensusLog.info(
                  logger,
                  Category.Recovery,
                  key.show,
                  role,
                  Event.SoftResetSuppressed,
                  "category" -> category,
                  "triggerCount" -> triggerCount.toString,
                  "softResetCount" -> softResetCount.toString,
                  "reason" -> "no_ready_peer_with_useful_declarations"
                ) >>
                  Metrics[F].incrementCounter(
                    "dag_consensus_soft_reset_suppressed_total",
                    Seq(Metrics.unsafeLabelName("reason") -> "no_ready_peer_with_useful_declarations")
                  ) >>
                  (SoftResetOutcome.SuppressedNoReadyPeerWithUsefulDeclarations: SoftResetOutcome).pure[F]
              else
                consensusStorage.softResetRoundState(key) >>
                  consensusStorage.tickSoftResetAtSameKey(key).flatMap { newCount =>
                    ConsensusLog.warn(
                      logger,
                      Category.Recovery,
                      key.show,
                      role,
                      Event.SoftResetTriggered,
                      "category" -> category,
                      "triggerCount" -> triggerCount.toString,
                      "softResetCount" -> newCount.toString,
                      "maxSoftResetsAtSameKey" -> consensusConfig.maxSoftResetsAtSameKey.toString
                    ) >>
                      Metrics[F].incrementCounter(
                        "dag_consensus_soft_reset_total",
                        Seq(Metrics.unsafeLabelName("category") -> category)
                      ) >>
                      consensusStorage.clearStaleLocalViewAtSameKey >>
                      (SoftResetOutcome.Fired: SoftResetOutcome).pure[F]
                  }
            }
        }
      }

      /** Escalate repeated artifact mismatches to download recovery when the local node has strong external evidence that the round has
        * moved past its local state. This is intentionally narrower than a generic "same key" recovery:
        *
        *   - it only runs after repeated artifact-validation failures,
        *   - it requires an external quorum of the round-start facilitators,
        *   - every counted peer must be Ready and locally observed ahead of this key,
        *   - it does not mutate the committee, view, leader, or facilitator hash.
        *
        * In alpha.107, .79 failed leader-artifact validation for 3127560, withdrew, soft-reset, and remained Ready at 3127559 while an
        * external quorum had already finalized 3127560. Keeping that peer Ready-but-stale poisoned the next round. This path turns that
        * condition into the existing incremental DownloadDaemon recovery instead of another local reset.
        */
      private def maybeTriggerArtifactMismatchCatchup(
        state: GlobalSnapshotConsensusState,
        role: String,
        failureCount: Int
      ): F[Boolean] =
        for {
          responsivePeers <- clusterStorage.getResponsivePeers
          readyIds = responsivePeers.filter(_.state === NodeState.Ready).map(_.id).toSet
          peerKeys <- consensusStorage.getPeerCurrentKeys
          roundStart = state.roundStartFacilitators.value
          // TODO(certified-catchup): This ahead-only predicate prevents same-key no-op
          // downloads, but it is still a tactical proxy. The production check should ask
          // whether a verified downloadable/certified outcome exists for this ordinal or a
          // higher ordinal. A peer can be behind by one snapshot while the recovery source is at
          // the same round key but has already finalized it.
          aheadExternal = roundStart.filter { peerId =>
            peerId =!= selfId &&
            readyIds.contains(peerId) &&
            peerKeys.get(peerId).exists(_ > state.key)
          }
          required = math.max(1, QuorumPolicy.fromFraction(roundStart.size, consensusConfig.quorumThresholdFraction))
          shouldRecover = aheadExternal.size >= required
          _ <-
            if (shouldRecover)
              ConsensusLog.warn(
                logger,
                Category.Recovery,
                state.key.show,
                role,
                Event.RecoveryStateTransition,
                "trigger" -> "artifact_mismatch_same_chain_catchup",
                "failureCount" -> failureCount.toString,
                "aheadExternal" -> aheadExternal.size.toString,
                "required" -> required.toString,
                "aheadExternalPeers" -> ConsensusLog.pids(aheadExternal.toList),
                "action" -> "incremental_recovery"
              ) >>
                Metrics[F].incrementCounter(
                  "dag_consensus_artifact_mismatch_catchup_total",
                  Seq(Metrics.unsafeLabelName("outcome") -> "triggered")
                ) >>
                nodeStorage.setRecoveryDownload >>
                nodeStorage
                  .tryModifyState(
                    Set[NodeState](NodeState.Ready, NodeState.WaitingForReady, NodeState.Observing),
                    NodeState.WaitingForDownload
                  )
                  .void
            else
              ConsensusLog.info(
                logger,
                Category.Recovery,
                state.key.show,
                role,
                Event.RecoveryStateTransition,
                "trigger" -> "artifact_mismatch_same_chain_catchup",
                "failureCount" -> failureCount.toString,
                "aheadExternal" -> aheadExternal.size.toString,
                "required" -> required.toString,
                "action" -> "suppressed_not_enough_ahead_external_facilitators"
              ) >>
                Metrics[F].incrementCounter(
                  "dag_consensus_artifact_mismatch_catchup_total",
                  Seq(Metrics.unsafeLabelName("outcome") -> "suppressed_not_enough_ahead_external_facilitators")
                )
        } yield shouldRecover

      /** Validate view/VCC invariants on an incoming proposal. Thin delegate to the shared `ProposalVccValidator.validate` helper so the
        * dag-l0 and currency-l0 advancers cannot drift on consensus-adjacent logic. See the helper's scaladoc for the full branch summary;
        * ProposalVccValidatorSuite pins every positive/negative path including the alpha.90 P0 #1 seed-view bypass and the alpha.90 issue 2
        * stale-VCC view-mismatch gate.
        *
        * Effectful since v33: the shared quorum-denominator-shrink decision (wall-clock anchored, see `QuorumDenominatorShrink`) is derived
        * per validation so a follower accepts a shrunken-quorum VCC/TC exactly when the escalation predicate holds, independent of any
        * local retry counters.
        */
      private def validateProposalVcc(
        state: GlobalSnapshotConsensusState,
        proposal: Proposal,
        facilitatorsHash: Hash
      ): F[Either[ProposalRejection, Unit]] =
        quorumShrinkDecision(state).map { shrinkDecision =>
          ProposalVccValidator.validate(
            proposalView = proposal.view,
            proposalHash = proposal.hash,
            proposalVcc = proposal.vcc,
            proposalTimeoutCertificate = proposal.timeoutCertificate,
            initialViewNumber = state.initialViewNumber,
            coreSize = state.coreFacilitators.value.size,
            facilitatorsHash = facilitatorsHash,
            lastSnapshotHash = state.lastOutcome.finished.snapshotHash,
            eligibleFacilitators = state.eligibleFacilitators.value.toSet,
            roundStartFacilitators = state.roundStartFacilitators.value.toSet,
            peerQuality = state.lastOutcome.peerQuality.toMap,
            quorumThresholdFraction = config.quorumThresholdFraction,
            minParticipationObservations = config.minParticipationObservations,
            quorumShrink = Some(shrinkDecision)
          )
        }

      /** Verify that every `Signed[ViewChangeVote]` inside the VCC has a valid cryptographic signature over the voter's actual payload.
        * Protects against an adversarial leader constructing a VCC with fabricated/unsigned votes. Each vote must have exactly one
        * signature proof (the voter's) that validates against the canonical-encoded `ViewChangeVote` bytes.
        */
      private def verifyVccSignatures(vcc: ViewChangeCertificate)(implicit hasher: Hasher[F]): F[Either[ProposalRejection, Unit]] =
        vcc.votes.toNonEmptyList.traverse { signedVote =>
          signedVote.hasValidSignature[F].map {
            case true  => Right(()): Either[ProposalRejection, Unit]
            case false => Left(ProposalRejection(signedVote.proofs.head.id.show.take(8)))
          }
        }.map { results =>
          val invalidPeers = results.toList.collect { case Left(pid) => pid.code }
          if (invalidPeers.isEmpty) Right(())
          else Left(ProposalRejection(s"vcc_invalid_signatures peers=${invalidPeers.mkString(",")}"))
        }

      private def verifyTcSignatures(tc: TimeoutCertificate)(implicit hasher: Hasher[F]): F[Either[ProposalRejection, Unit]] =
        tc.votes.toNonEmptyList.toList.collect {
          case signedVote if signedVote.proofs.size != 1 =>
            signedVote.proofs.head.id.show.take(8)
        } match {
          case invalidProofCounts if invalidProofCounts.nonEmpty =>
            Applicative[F].pure(Left(ProposalRejection(s"tc_invalid_proof_count peers=${invalidProofCounts.mkString(",")}")))
          case _ =>
            tc.votes.toNonEmptyList.traverse { signedVote =>
              signedVote.hasValidSignature[F].map {
                case true  => Right(()): Either[ProposalRejection, Unit]
                case false => Left(ProposalRejection(signedVote.proofs.head.id.show.take(8)))
              }
            }.map { results =>
              val invalidPeers = results.toList.collect { case Left(pid) => pid.code }
              if (invalidPeers.isEmpty) Right(())
              else Left(ProposalRejection(s"tc_invalid_signatures peers=${invalidPeers.mkString(",")}"))
            }
        }

      // Phase B1 bootstrap gate. Mirrors Phase 4's penalty-accrual suppression: while the chain
      // has not yet produced a snapshot with >= bootstrapCompleteProofsThreshold signers, the
      // cluster is still stabilizing and any B1 activity is unsafe (causes cascading committee
      // splits as observed in early fork-recovery E2E runs).
      private def isInBootstrap(state: GlobalSnapshotConsensusState): Boolean =
        !state.lastOutcome.recentProofSizes.values.exists(_ >= config.bootstrapCompleteProofsThreshold)

      /** Validate structural invariants on every embedded `EvictionCertificate`:
        *   - reject entirely if the node is still in bootstrap (honest leaders must not embed certs before the cluster has stabilized)
        *   - at least `q` votes (quorum at this round's committee)
        *   - all votes within a cert agree on (targetPeer, reason, facilitatorsHash)
        *   - cert's facilitatorsHash matches the round's facilitatorsHash
        *   - target peer is in the current committee
        *   - no duplicate certs for the same target within a proposal
        */
      private def validateProposalEcs(
        state: GlobalSnapshotConsensusState,
        proposal: Proposal,
        facilitatorsHash: Hash
      ): Either[ProposalRejection, Unit] = {
        if (isInBootstrap(state) && proposal.evictionCertificates.nonEmpty)
          return Left(ProposalRejection(s"ecs_rejected_in_bootstrap count=${proposal.evictionCertificates.size}"))
        // v19: quorum threshold computed against the Core committee only. The
        // `committee` set retains the full round-start view because eviction targets are
        // checked for round-start membership (a Tier 1 or Tier 0 peer can still be the
        // target of an eviction). Threshold and target-membership therefore decouple --
        // quorum n = Core size, target membership = round-start membership.
        //
        // Voter/signer membership widened from `roundStartFacilitators` to
        // `eligibleFacilitators - target`. See StateTransitions.scala assembly site for full
        // rationale. Both the assembly site and this re-validation must agree on the witness
        // pool, otherwise leaders would assemble certs that followers reject. Integer math via
        // `QuorumPolicy.fromFraction`.
        val n = state.coreFacilitators.value.size
        val q = math.max(1, QuorumPolicy.fromFraction(n, config.quorumThresholdFraction))
        val committee = state.roundStartFacilitators.value.toSet
        val expectedLastSnap: Hash = state.lastOutcome.finished.snapshotHash

        @scala.annotation.tailrec
        def loop(remaining: List[EvictionCertificate], seenTargets: Set[PeerId]): Either[ProposalRejection, Unit] =
          remaining match {
            case Nil => Right(())
            case cert :: tail =>
              if (seenTargets.contains(cert.targetPeer))
                Left(ProposalRejection(s"ecs_duplicate_target target=${cert.targetPeer.show.take(8)}"))
              else if (cert.facilitatorsHash =!= facilitatorsHash)
                Left(
                  ProposalRejection(
                    s"ecs_facilitators_mismatch target=${cert.targetPeer.show.take(8)} " +
                      s"certFacHash=${cert.facilitatorsHash.show.take(8)} ours=${facilitatorsHash.show.take(8)}"
                  )
                )
              // Bind cert to current tip. Prevents replay of stale
              // signed-vote quorums from earlier tips that happened to share facilitatorsHash.
              else if (cert.lastSnapshotHash =!= expectedLastSnap)
                Left(
                  ProposalRejection(
                    s"ecs_last_snap_mismatch target=${cert.targetPeer.show.take(8)} " +
                      s"certLastSnap=${cert.lastSnapshotHash.show.take(8)} ours=${expectedLastSnap.show.take(8)}"
                  )
                )
              else if (!committee.contains(cert.targetPeer))
                Left(ProposalRejection(s"ecs_target_not_in_committee target=${cert.targetPeer.show.take(8)}"))
              else if (cert.votes.size < q)
                Left(ProposalRejection(s"ecs_under_quorum target=${cert.targetPeer.show.take(8)} votes=${cert.votes.size} required=$q"))
              else {
                val mismatched = cert.votes.toList.find { signed =>
                  signed.value.targetPeer =!= cert.targetPeer ||
                  signed.value.reason =!= cert.reason ||
                  signed.value.facilitatorsHash =!= cert.facilitatorsHash ||
                  signed.value.lastSnapshotHash =!= cert.lastSnapshotHash
                }
                mismatched match {
                  case Some(bad) =>
                    Left(
                      ProposalRejection(
                        s"ecs_vote_field_mismatch target=${cert.targetPeer.show.take(8)} voter=${bad.proofs.head.id.show.take(8)}"
                      )
                    )
                  case None =>
                    // Pool widens from the round-start committee to the union of
                    // `eligibleFacilitators` and historical participants in `lastOutcome.peerQuality`
                    // (participated >= minParticipationObservations). Both inputs are projections of
                    // the previous signed outcome; both sides of the round (assembler in
                    // StateTransitions.checkEvictionAssembly and follower here) compute the
                    // byte-identical pool via the shared WitnessPool helper. The quorum denominator
                    // stays committee-sized -- only the set of valid witness signers widens.
                    val witnessPool = WitnessPool
                      .forTarget(
                        state.eligibleFacilitators.value.toSet,
                        state.lastOutcome.peerQuality.toMap,
                        config.minParticipationObservations,
                        cert.targetPeer
                      )
                      .union(state.roundStartFacilitators.value.toSet - cert.targetPeer)
                    val nonWitnessPoolVoter = cert.votes.toList.find(sv => !witnessPool.contains(sv.proofs.head.id.toPeerId))
                    nonWitnessPoolVoter match {
                      case Some(bad) =>
                        Left(
                          ProposalRejection(
                            s"ecs_voter_not_in_committee target=${cert.targetPeer.show.take(8)} voter=${bad.proofs.head.id.show.take(8)}"
                          )
                        )
                      case None => loop(tail, seenTargets + cert.targetPeer)
                    }
                }
              }
          }
        loop(proposal.evictionCertificates, Set.empty)
      }

      /** Verify every `Signed[EvictionVote]` inside every embedded `EvictionCertificate` has a valid cryptographic signature. Mirrors
        * `verifyVccSignatures`. Protects against a leader constructing certificates from fabricated/unsigned votes.
        */
      private def verifyEcsSignatures(
        proposal: Proposal
      )(implicit hasher: Hasher[F]): F[Either[ProposalRejection, Unit]] =
        proposal.evictionCertificates.flatTraverse { cert =>
          cert.votes.toNonEmptyList.toList.traverse { signedVote =>
            signedVote.hasValidSignature[F].map {
              case true => Right(()): Either[ProposalRejection, Unit]
              case false =>
                Left(ProposalRejection(s"target=${cert.targetPeer.show.take(8)} voter=${signedVote.proofs.head.id.show.take(8)}"))
            }
          }
        }.map { results =>
          val invalid = results.collect { case Left(msg) => msg.code }
          if (invalid.isEmpty) Right(())
          else Left(ProposalRejection(s"ecs_invalid_signatures [${invalid.mkString("; ")}]"))
        }

      /** Validate structural invariants on every embedded `AdmissionCertificate`. Mirrors `validateProposalEcs` with symmetric checks for
        * re-admission targets:
        *   - reject during bootstrap (no B2 activity until cluster stabilizes)
        *   - at least `q` votes (quorum at this round's committee)
        *   - all votes within a cert agree on (targetPeer, reason, facilitatorsHash)
        *   - cert's facilitatorsHash matches the round's facilitatorsHash
        *   - target peer is either in `readmissionCountdown` or quorum-certified as a new ReadyAtTip candidate
        *   - target peer is NOT currently in the committee (re-admitting an active facilitator is nonsensical)
        *   - target peer is not under an active removal penalty
        *   - voters are all members of the current committee
        *   - no duplicate certs for the same target within a proposal
        */
      private def validateProposalAcs(
        state: GlobalSnapshotConsensusState,
        proposal: Proposal,
        facilitatorsHash: Hash
      ): Either[ProposalRejection, Unit] = {
        val maxAdmissionCertificates = math.max(0, config.activeAdmissionMaxExpansionPerRound)
        if (proposal.admissionCertificates.size > maxAdmissionCertificates)
          return Left(
            ProposalRejection(
              s"acs_too_many count=${proposal.admissionCertificates.size} max=$maxAdmissionCertificates"
            )
          )
        // v19: quorum threshold computed against the Core committee only -- see
        // validateProposalEcs for the full decoupled-threshold-vs-membership rationale.
        // Integer math via `QuorumPolicy.fromFraction`.
        val n = state.coreFacilitators.value.size
        val q = math.max(1, QuorumPolicy.fromFraction(n, config.quorumThresholdFraction))
        val committee = state.roundStartFacilitators.value.toSet
        val probation = state.lastOutcome.readmissionCountdown.keySet
        val penalized = state.lastOutcome.removalPenalties.filter(_._2 > 0).keySet
        val expectedLastSnap: Hash = state.lastOutcome.finished.snapshotHash

        @scala.annotation.tailrec
        def loop(remaining: List[AdmissionCertificate], seenTargets: Set[PeerId]): Either[ProposalRejection, Unit] =
          remaining match {
            case Nil => Right(())
            case cert :: tail =>
              if (seenTargets.contains(cert.targetPeer))
                Left(ProposalRejection(s"acs_duplicate_target target=${cert.targetPeer.show.take(8)}"))
              else if (cert.facilitatorsHash =!= facilitatorsHash)
                Left(
                  ProposalRejection(
                    s"acs_facilitators_mismatch target=${cert.targetPeer.show.take(8)} " +
                      s"certFacHash=${cert.facilitatorsHash.show.take(8)} ours=${facilitatorsHash.show.take(8)}"
                  )
                )
              else if (cert.lastSnapshotHash =!= expectedLastSnap)
                Left(
                  ProposalRejection(
                    s"acs_last_snap_mismatch target=${cert.targetPeer.show.take(8)} " +
                      s"certLastSnap=${cert.lastSnapshotHash.show.take(8)} ours=${expectedLastSnap.show.take(8)}"
                  )
                )
              else if (committee.contains(cert.targetPeer))
                Left(ProposalRejection(s"acs_target_already_in_committee target=${cert.targetPeer.show.take(8)}"))
              else if (penalized.contains(cert.targetPeer))
                Left(ProposalRejection(s"acs_target_penalized target=${cert.targetPeer.show.take(8)}"))
              else if (!probation.contains(cert.targetPeer) && cert.reason =!= AdmissionReason.ReadyAtTip)
                Left(ProposalRejection(s"acs_target_not_admissible target=${cert.targetPeer.show.take(8)} reason=${cert.reason.show}"))
              else if (cert.votes.size < q)
                Left(ProposalRejection(s"acs_under_quorum target=${cert.targetPeer.show.take(8)} votes=${cert.votes.size} required=$q"))
              else {
                val mismatched = cert.votes.toList.find { signed =>
                  signed.value.targetPeer =!= cert.targetPeer ||
                  signed.value.reason =!= cert.reason ||
                  signed.value.facilitatorsHash =!= cert.facilitatorsHash ||
                  signed.value.lastSnapshotHash =!= cert.lastSnapshotHash
                }
                mismatched match {
                  case Some(bad) =>
                    Left(
                      ProposalRejection(
                        s"acs_vote_field_mismatch target=${cert.targetPeer.show.take(8)} voter=${bad.proofs.head.id.show.take(8)}"
                      )
                    )
                  case None =>
                    // Symmetric widening with B1 -- see validateProposalEcs above.
                    val witnessPool = WitnessPool.forTarget(
                      state.eligibleFacilitators.value.toSet,
                      state.lastOutcome.peerQuality.toMap,
                      config.minParticipationObservations,
                      cert.targetPeer
                    )
                    val nonWitnessPoolVoter = cert.votes.toList.find(sv => !witnessPool.contains(sv.proofs.head.id.toPeerId))
                    nonWitnessPoolVoter match {
                      case Some(bad) =>
                        Left(
                          ProposalRejection(
                            s"acs_voter_not_in_committee target=${cert.targetPeer.show.take(8)} voter=${bad.proofs.head.id.show.take(8)}"
                          )
                        )
                      case None => loop(tail, seenTargets + cert.targetPeer)
                    }
                }
              }
          }
        loop(proposal.admissionCertificates, Set.empty)
      }

      /** v7 (flaky-byzantine): validate the leader's observedResponders payload. The set is bound to the leader by the rumor envelope's
        * signature (RumorValidator.scala:50 — signers.contains(rumor.origin)); we only need a deterministic subset check here. Codex turn 2
        * fix #1: bootstrap is empty-only. Below-quorum count is a warning metric, NOT a hard reject — honest withdrawals between facility-
        * acceptance and proposal-build can shrink state.facilitators legitimately, and rejecting on count would falsely fail valid rounds.
        */
      private def validateProposalObservedResponders(
        state: GlobalSnapshotConsensusState,
        proposal: Proposal
      ): Either[ProposalRejection, Unit] = {
        if (isInBootstrap(state) && proposal.observedResponders.nonEmpty)
          return Left(ProposalRejection(s"obs_resp_rejected_in_bootstrap count=${proposal.observedResponders.size}"))
        val committee = state.roundStartFacilitators.value.toSet
        val notInCommittee = proposal.observedResponders.toSet -- committee
        if (notInCommittee.nonEmpty)
          Left(
            ProposalRejection(
              s"obs_resp_not_in_committee count=${notInCommittee.size} sample=${notInCommittee.take(3).map(_.show.take(8)).mkString(",")}"
            )
          )
        else
          Right(())
      }

      /** Verify every `Signed[AdmissionVote]` inside every embedded `AdmissionCertificate` has a valid cryptographic signature. Mirrors
        * `verifyEcsSignatures`.
        */
      private def verifyAcsSignatures(
        proposal: Proposal
      )(implicit hasher: Hasher[F]): F[Either[ProposalRejection, Unit]] =
        proposal.admissionCertificates.flatTraverse { cert =>
          cert.votes.toNonEmptyList.toList.traverse { signedVote =>
            signedVote.hasValidSignature[F].map {
              case true => Right(()): Either[ProposalRejection, Unit]
              case false =>
                Left(ProposalRejection(s"target=${cert.targetPeer.show.take(8)} voter=${signedVote.proofs.head.id.show.take(8)}"))
            }
          }
        }.map { results =>
          val invalid = results.collect { case Left(msg) => msg.code }
          if (invalid.isEmpty) Right(())
          else Left(ProposalRejection(s"acs_invalid_signatures [${invalid.mkString("; ")}]"))
        }

      private def resolveLeaderProposal(
        state: GlobalSnapshotConsensusState,
        status: CollectingProposals,
        resources: ConsensusResources[GlobalSnapshotArtifact, GlobalConsensusKind],
        leaderProposal: Proposal
      )(implicit hasher: Hasher[F]): F[Option[Transition]] = {
        val role = if (selfId === state.leader) "LEADER" else "FOLLOWER"
        // Alpha.93 Fix A + Fix C (see `project_alpha92_wedge_may21.md`):
        // The alpha.92 9h wedge at ord 3127095 was caused by a frozen `peerDeclarationsMap[leader].proposal`
        // slot. .45's view-16 proposal arrived at .193 BEFORE .193 entered the round under .45's leadership;
        // .193 then re-attempted at `initialViewNumber=18` but the cached `Proposal(view=16, vcc=None)` no
        // longer matched the seed-view bypass (`16 != 18`), so the validator rejected on every
        // CollectingProposals re-evaluation -- 10,333 times in ~9h. `addProposal` first-write-wins for
        // higher-view-without-VCC blocked replacement, and the leader couldn't re-emit because `vccMissing`
        // aborted at every higher view. Self-heal: when the rejection IS this stale-slot pattern
        // (proposalView < initialViewNumber AND vcc.isEmpty), prune the slot so a fresh broadcast can
        // populate it (or, if the leader stays stuck, the round abandons cleanly without 1000+ rejections).
        // Fix C: increment `dag_consensus_stale_proposal_rejection_total{peer_id=<leader>}` so future
        // occurrences are visible in seconds via Prometheus alerts instead of after hours of log review.
        def logVccReject(rejection: ProposalRejection): F[Option[Transition]] = {
          val isStaleSlotPattern =
            leaderProposal.view < state.initialViewNumber.toLong &&
              leaderProposal.vcc.isEmpty &&
              leaderProposal.timeoutCertificate.isEmpty &&
              rejection.isMissingViewCert
          // Alpha.97 stale-local-view detection. Distinct from the stale-slot pattern
          // above: the stale-slot fires when our recorded `initialViewNumber` advanced
          // past the leader's proposalView (the slot self-heals via prune). Stale-local-
          // view fires the opposite way: leader is AHEAD of our local view, our local
          // round state is the wedge. Recover via the in-place soft reset.
          val isStaleLocalViewPattern =
            !isStaleSlotPattern && rejection.triggersStaleViewRecovery
          val maybePruneAndMeter =
            if (isStaleSlotPattern)
              Metrics[F].incrementCounter(
                "dag_consensus_stale_proposal_rejection_total",
                Seq(Metrics.unsafeLabelName("peer_id") -> ConsensusLog.pid(state.leader))
              ) >>
                consensusStorage.pruneStaleProposalSlots(state.key, state.initialViewNumber.toLong)
            else Applicative[F].unit
          val maybeTrySoftReset =
            if (isStaleLocalViewPattern)
              consensusStorage.tickStaleLocalViewAtSameKey(state.key).flatMap { rejectionCount =>
                Metrics[F].incrementCounter("dag_consensus_stale_local_view_rejection_total") >>
                  Applicative[F].whenA(rejectionCount >= consensusConfig.maxStaleLocalViewRejections) {
                    trySoftResetAtSameKey(state.key, "stale_local_view", rejectionCount, role).flatMap {
                      case SoftResetOutcome.Fired =>
                        Applicative[F].unit
                      case SoftResetOutcome.SuppressedBudgetExhausted =>
                        // Wedge is unrecoverable in place at this key. Escalate to the
                        // existing heavy Download recovery -- DownloadDaemon will pick up
                        // the NodeState flip and resync from peers.
                        ConsensusLog.warn(
                          logger,
                          Category.Recovery,
                          state.key.show,
                          role,
                          Event.RecoveryStateTransition,
                          "trigger" -> "stale_local_view_soft_reset_budget_exhausted",
                          "rejectionCount" -> rejectionCount.toString,
                          "action" -> "incremental_recovery"
                        ) >>
                          consensusStorage.clearStaleLocalViewAtSameKey >>
                          nodeStorage.setRecoveryDownload >>
                          nodeStorage
                            .tryModifyState(
                              Set[NodeState](NodeState.Ready, NodeState.WaitingForReady),
                              NodeState.WaitingForDownload
                            )
                            .void
                      case SoftResetOutcome.SuppressedNoReadyPeerWithUsefulDeclarations =>
                        // No Ready peer with usable declarations -- the cluster lacks a
                        // proven recovery source. Going to WFD here would just feed the
                        // recovery cascade (Core peer leaves Ready precisely when the
                        // network has nothing to recover FROM). Fall through to normal
                        // stall handling; StallDetector / AbandonmentTracker will fire
                        // when the situation persists with peers actually ahead.
                        Applicative[F].unit
                    }
                  }
              }
            else Applicative[F].unit
          ConsensusLog
            .warn(
              logger,
              Category.Validation,
              state.key.show,
              role,
              Event.ValidationFailed,
              "reason" -> s"vcc_validation: ${rejection.code}",
              "leader" -> ConsensusLog.pid(state.leader),
              "view" -> state.viewNumber.toString
            ) >> maybePruneAndMeter >> maybeTrySoftReset.as(none[Transition])
        }
        def logEcsReject(rejection: ProposalRejection): F[Option[Transition]] =
          ConsensusLog
            .warn(
              logger,
              Category.Validation,
              state.key.show,
              role,
              Event.ValidationFailed,
              "reason" -> s"ecs_validation: ${rejection.code}",
              "leader" -> ConsensusLog.pid(state.leader),
              "view" -> state.viewNumber.toString
            )
            .as(none[Transition])
        def logAcsReject(rejection: ProposalRejection): F[Option[Transition]] =
          ConsensusLog
            .warn(
              logger,
              Category.Validation,
              state.key.show,
              role,
              Event.ValidationFailed,
              "reason" -> s"acs_validation: ${rejection.code}",
              "leader" -> ConsensusLog.pid(state.leader),
              "view" -> state.viewNumber.toString
            )
            .as(none[Transition])
        validateProposalVcc(state, leaderProposal, status.facilitatorsHash).flatMap {
          case Left(reason) => logVccReject(reason)
          case Right(_) =>
            val afterVccSig: F[Option[Transition]] = leaderProposal.vcc match {
              case Some(vcc) =>
                verifyVccSignatures(vcc).flatMap {
                  case Left(reason) => logVccReject(reason)
                  case Right(_)     => resolveLeaderProposalInner(state, status, resources, leaderProposal)
                }
              case None => resolveLeaderProposalInner(state, status, resources, leaderProposal)
            }
            val afterViewCertSig: F[Option[Transition]] = leaderProposal.timeoutCertificate match {
              case Some(tc) =>
                verifyTcSignatures(tc).flatMap {
                  case Left(reason) => logVccReject(reason)
                  case Right(_)     => afterVccSig
                }
              case None => afterVccSig
            }
            // B1 eviction-certificate validation layered onto the same path. Validation
            // short-circuits if any cert fails so an adversarial leader cannot smuggle
            // malformed evictions into proposal acceptance. B2 admission-certificate
            // validation layers on top with the same short-circuit semantics: VCC → ECS
            // → ACS → innerResolve. Any failure at any tier aborts acceptance.
            val afterEcs: F[Option[Transition]] =
              validateProposalEcs(state, leaderProposal, status.facilitatorsHash) match {
                case Left(reason) => logEcsReject(reason)
                case Right(_) =>
                  if (leaderProposal.evictionCertificates.isEmpty) afterViewCertSig
                  else
                    verifyEcsSignatures(leaderProposal).flatMap {
                      case Left(reason) => logEcsReject(reason)
                      case Right(_)     => afterViewCertSig
                    }
              }
            val afterAcs: F[Option[Transition]] =
              validateProposalAcs(state, leaderProposal, status.facilitatorsHash) match {
                case Left(reason) => logAcsReject(reason)
                case Right(_) =>
                  if (leaderProposal.admissionCertificates.isEmpty) afterEcs
                  else
                    verifyAcsSignatures(leaderProposal).flatMap {
                      case Left(reason) => logAcsReject(reason)
                      case Right(_)     => afterEcs
                    }
              }
            // v7 (flaky-byzantine): observedResponders subset validation. No signed-vote layer
            // here — the rumor envelope binds the set to the leader; only deterministic subset
            // check is needed. Below-quorum count emits a warning log but does NOT reject.
            validateProposalObservedResponders(state, leaderProposal) match {
              case Left(rejection) =>
                ConsensusLog
                  .warn(
                    logger,
                    Category.Validation,
                    state.key.show,
                    role,
                    Event.ValidationFailed,
                    "reason" -> s"obs_resp_validation: ${rejection.code}",
                    "leader" -> ConsensusLog.pid(state.leader),
                    "view" -> state.viewNumber.toString
                  )
                  .as(none[Transition])
              case Right(_) =>
                // v19: observedResponders quorum gate computed against the Core committee.
                // Integer math via `QuorumPolicy.fromFraction`.
                val n = state.coreFacilitators.value.size
                val q = math.max(1, QuorumPolicy.fromFraction(n, config.quorumThresholdFraction))
                val below = leaderProposal.observedResponders.size < q && !isInBootstrap(state)
                ConsensusLog
                  .warn(
                    logger,
                    Category.Validation,
                    state.key.show,
                    role,
                    Event.ValidationFailed,
                    "reason" -> "obs_resp_below_quorum",
                    "size" -> leaderProposal.observedResponders.size.toString,
                    "quorum" -> q.toString,
                    "view" -> state.viewNumber.toString
                  )
                  .whenA(below) >> afterAcs
            }
        }
      }

      private def resolveLeaderProposalInner(
        state: GlobalSnapshotConsensusState,
        status: CollectingProposals,
        resources: ConsensusResources[GlobalSnapshotArtifact, GlobalConsensusKind],
        leaderProposal: Proposal
      )(implicit hasher: Hasher[F]): F[Option[Transition]] = {
        val role = if (selfId === state.leader) "LEADER" else "FOLLOWER"
        if (leaderProposal.hash === status.proposalArtifactInfo.hash) {
          // Leader's artifact matches our own — use local ArtifactInfo (avoids re-validation)
          ConsensusLog.debug(
            logger,
            Category.Validation,
            state.key.show,
            role,
            Event.ArtifactHashMatch,
            "hash" -> leaderProposal.hash.show.take(12),
            "match" -> "true"
          ) >>
            ConsensusLog.info(
              logger,
              Category.Phase,
              state.key.show,
              role,
              Event.ProposalsToSignatures,
              "matchesOwn" -> "true",
              "hash" -> leaderProposal.hash.show.take(8),
              "trigger" -> status.majorityTrigger.toString,
              "leader" -> ConsensusLog.pid(state.leader),
              "view" -> state.viewNumber.toString
            ) >>
            Metrics[F].incrementCounter("dag_consensus_proposal_affinity_match") >>
            buildSignatureTransition(
              state,
              status,
              status.proposalArtifactInfo,
              List(leaderProposal.hash),
              leaderProposal.vcc,
              leaderProposal.timeoutCertificate,
              leaderProposal.evictionCertificates,
              leaderProposal.admissionCertificates,
              leaderProposal.observedResponders,
              leaderProposal.observedSelfHealth
            )
        } else {
          // Leader proposed a different artifact -- apply it via the follower path.
          //
          // Note (alpha.94): a previous HotStuff-inspired "facilitator-set-mismatch adoption"
          // branch lived here. It compared `Facility.facilitatorsHash` (which by construction
          // carries the PARENT outcome's hash, see `GlobalSnapshotConsensusStateCreator.scala`
          // build site) against `status.facilitatorsHash` (CURRENT round's hash) and treated any
          // mismatch as evidence the leader had a different live committee, then "adopted" the
          // leader's view by validating against `state.facilitators - selfId`. The comparison
          // was unsound (parent vs current) so it false-positive fired every time the committee
          // changed between rounds, and the adoption set was a heuristic that did not reflect
          // what the leader actually declared. Net effect was the alpha.93 observation loop
          // (`facilitator_set_mismatch_revalidate` repeating every ~300ms with self-signature
          // withdrawal). `checkForkByFacilitatorsHash` at line ~1046 already does correct
          // current-vs-current validation against the leader's PROPOSAL, so mismatches at this
          // layer abandon cleanly and let view-change rotate the leader -- the right behavior
          // for a node that genuinely disagrees with the leader. The 5-arg
          // `validateLeaderArtifact` overload taking a custom facilitator set was removed with
          // this block; the canonical roundStartFacilitators path is the only validator now.
          //
          // createContext (follower path) mutates the shared MptStore. We take a savepoint so
          // we can restore on IO-level failure to prevent partial state from cascading to
          // future rounds.
          resources.artifacts.get(leaderProposal.hash) match {
            case Some(leaderArtifact) =>
              // Restore the proposal savepoint (from line 466) to undo PATH 1's MPT
              // mutations before re-deriving the leader's artifact. Without this,
              // PATH 2's sync stacks on top of PATH 1's entries, corrupting the MPT
              // with a mix of both computations' state changes.
              proposalSavepointRef.get.flatMap(_.filter(_._1 === state.key).traverse_(_._2.restore)) >>
                mptStore.savepoint.flatMap { sp =>
                  val validate =
                    ConsensusLog.info(
                      logger,
                      Category.Validation,
                      state.key.show,
                      "Validator",
                      Event.ValidatingLeaderArtifact,
                      "leaderHash" -> leaderProposal.hash.show.take(8),
                      "ownHash" -> status.proposalArtifactInfo.hash.show.take(8)
                    ) >>
                      validateLeaderArtifact(state, status, leaderArtifact, leaderProposal.hash).flatMap {
                        case Right(leaderInfo) =>
                          // Validation succeeded -- MptStore mutations are correct, keep them
                          ConsensusLog.info(
                            logger,
                            Category.Validation,
                            state.key.show,
                            role,
                            Event.ArtifactRevalidated,
                            "matchesOwn" -> "false",
                            "leaderHash" -> leaderProposal.hash.show.take(8),
                            "ownHash" -> status.proposalArtifactInfo.hash.show.take(8),
                            "trigger" -> status.majorityTrigger.toString,
                            "leader" -> ConsensusLog.pid(state.leader),
                            "view" -> state.viewNumber.toString
                          ) >>
                            Metrics[F].incrementCounter("dag_consensus_proposal_affinity_mismatch_accepted") >>
                            buildSignatureTransition(
                              state,
                              status,
                              leaderInfo,
                              List(leaderProposal.hash),
                              leaderProposal.vcc,
                              leaderProposal.timeoutCertificate,
                              leaderProposal.evictionCertificates,
                              leaderProposal.admissionCertificates,
                              leaderProposal.observedResponders,
                              leaderProposal.observedSelfHealth
                            )
                        case Left(invalidArtifact) =>
                          // Validation failed -- restore MptStore to pre-validation state
                          val diffDetail = describeInvalidArtifact(invalidArtifact)
                          val ownCtx = status.proposalArtifactInfo.context
                          val ctxDigest = contextDigest(ownCtx)
                          val baseFields = Seq(
                            "leaderHash" -> leaderProposal.hash.show.take(8),
                            "ownHash" -> status.proposalArtifactInfo.hash.show.take(8),
                            "leader" -> ConsensusLog.pid(state.leader),
                            "view" -> state.viewNumber.toString,
                            "reason" -> diffDetail
                          )
                          sp.restore >>
                            artifactMismatchDiagnostics(
                              invalidArtifact,
                              leaderProposal.hash,
                              status.proposalArtifactInfo.hash
                            ).flatMap { diagnosticFields =>
                              ConsensusLog.warn(
                                logger,
                                Category.Validation,
                                state.key.show,
                                role,
                                Event.ValidationFailed,
                                (baseFields ++ diagnosticFields): _*
                              )
                            } >>
                            ConsensusLog.info(
                              logger,
                              Category.Validation,
                              state.key.show,
                              role,
                              Event.OwnContextDigest,
                              "detail" -> ctxDigest
                            ) >>
                            ConsensusLog.info(
                              logger,
                              Category.Phase,
                              state.key.show,
                              role,
                              Event.WithdrawValidationFail,
                              "reason" -> "proposal_validation_failed",
                              "mptStoreRestored" -> "true"
                            ) >>
                            gossip.spread(
                              ConsensusWithdrawPeerDeclaration(state.key, GlobalConsensusKind.Signature: GlobalConsensusKind)
                            ) >>
                            Metrics[F].incrementCounter("dag_consensus_proposal_validation_failure") >>
                            Metrics[F].incrementCounter("dag_consensus_withdrawal_sent") >>
                            // Track consecutive validation failures at this ordinal. After repeated
                            // failures (e.g., divergent MPT from network isolation), trigger an
                            // incremental recovery. The incremental path resyncs MptStore from the
                            // downloaded checkpoint data, which clears the divergent local state
                            // without the cost of a full re-download from genesis.
                            validationFailureCountRef.modify {
                              case (Some(k), count) if k === state.key => ((state.key.some, count + 1), count + 1)
                              case _                                   => ((state.key.some, 1), 1)
                            }.flatMap { count =>
                              // Alpha.97: when the artifact-hash mismatch count crosses the heavy-
                              // recovery threshold, FIRST attempt an in-place soft reset that keeps
                              // the node Ready. The soft reset clears volatile round state
                              // (artifacts, VCC, vote locks, withdrawals) while preserving the per-
                              // peer declaration map, so the round can re-evaluate from observed
                              // peer declarations without taking the node out of Core. Falls through
                              // to the existing heavy Download recovery only when the soft reset is
                              // suppressed (budget exhausted, or no useful declarations) or has
                              // already fired its budget at this key without resolving.
                              if (count >= maxConsecutiveValidationFailures)
                                maybeTriggerArtifactMismatchCatchup(state, role, count).flatMap {
                                  case true =>
                                    validationFailureCountRef.set((none, 0))
                                  case false =>
                                    trySoftResetAtSameKey(state.key, "artifact_mismatch", count, role).flatMap {
                                      case SoftResetOutcome.Fired =>
                                        validationFailureCountRef.set((none, 0))
                                      case SoftResetOutcome.SuppressedBudgetExhausted =>
                                        ConsensusLog.warn(
                                          logger,
                                          Category.Recovery,
                                          state.key.show,
                                          role,
                                          Event.RecoveryStateTransition,
                                          "trigger" -> "consecutive_validation_failures",
                                          "count" -> count.toString,
                                          "action" -> "incremental_recovery"
                                        ) >>
                                          validationFailureCountRef.set((none, 0)) >>
                                          // Set recovery download flag so DownloadDaemon uses the incremental
                                          // recovery path. The incremental path now properly syncs MptStore from
                                          // the downloaded snapshot's checkpoint data (no full rebuild needed).
                                          nodeStorage.setRecoveryDownload >>
                                          nodeStorage
                                            .tryModifyState(
                                              Set[NodeState](NodeState.Ready, NodeState.WaitingForReady),
                                              NodeState.WaitingForDownload
                                            )
                                            .void
                                      case SoftResetOutcome.SuppressedNoReadyPeerWithUsefulDeclarations =>
                                        // No Ready peer with usable declarations -- the cluster lacks a
                                        // proven recovery source. Forcing WFD here would worsen the cascade.
                                        // Keep the validation-failure count so the next failure can re-try
                                        // soft reset once a Ready peer surfaces.
                                        Async[F].unit
                                    }
                                }
                              else
                                Async[F].unit
                            } >>
                            none[Transition].pure[F]
                      }

                  // Use guaranteeCase to restore MptStore on any unexpected exception.
                  // Without this, an IO-level failure in validateLeaderArtifact would skip
                  // sp.restore, leaving partial MptStore state that poisons future rounds.
                  Async[F].guaranteeCase(validate) {
                    case Outcome.Errored(_) | Outcome.Canceled() =>
                      sp.restore >>
                        ConsensusLog.error(logger, Category.Lifecycle, state.key.show, role, Event.MptRestoredAfterFailure)
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
        // Use canonical round-start committee so every validator reaches the same accept/reject
        // decision regardless of the order in which they observed mid-round withdrawals. Using the
        // mutable state.facilitators allowed artifact validation to diverge across nodes (same
        // class as the ord-5 facilitatorsHash fork, but in the artifact plane).
        //
        // Alpha.94: 5-arg overload (custom facilitator set) was removed with the deletion of the
        // facilitator-set-mismatch adoption branch above. The roundStartFacilitators set is the
        // only sanctioned committee for artifact re-derivation.
        state.lastOutcome.finished.signedMajorityArtifact.toHashed.flatMap { hashedLast =>
          consensusFns
            .validateArtifact(
              hashedLast.signed,
              state.lastOutcome.finished.context,
              status.majorityTrigger,
              artifact,
              state.roundStartFacilitators.value.toSet,
              getGlobalSnapshotByOrdinal,
              // v32 (stage 4): re-pack the evidence-only peerHistory from the validator's own
              // lastOutcome -- must match the leader's createArtifact packing byte-identically.
              Some(state.lastOutcome.signedArtifactPeerHistory)
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
          val leaderScAddrs = leader.stateChannelSnapshots.keySet
          val ownScAddrs = own.stateChannelSnapshots.keySet
          val onlyLeader = leaderScAddrs -- ownScAddrs
          val onlyOwn = ownScAddrs -- leaderScAddrs
          val rewardsDiff: List[String] =
            if (leader.rewards === own.rewards) Nil
            else {
              val onlyInLeader = leader.rewards -- own.rewards
              val onlyInOwn = own.rewards -- leader.rewards
              List(
                Some(s"rewards(leader=${leader.rewards.size},own=${own.rewards.size})"),
                Option.when(onlyInLeader.nonEmpty)(
                  s"rewardsOnlyInLeader=[${onlyInLeader.toList.map(r => s"${r.destination.show.take(8)}:${r.amount.value.value}").mkString(",")}]"
                ),
                Option.when(onlyInOwn.nonEmpty)(
                  s"rewardsOnlyInOwn=[${onlyInOwn.toList.map(r => s"${r.destination.show.take(8)}:${r.amount.value.value}").mkString(",")}]"
                )
              ).flatten
            }
          val stateProofDiff: List[String] =
            if (leader.stateProof === own.stateProof) Nil
            else {
              val lp = leader.stateProof
              val op = own.stateProof
              val spDiffs: List[String] = List(
                Option.when(lp.lastStateChannelSnapshotHashesProof =!= op.lastStateChannelSnapshotHashesProof)(
                  s"scHashesProof(l=${lp.lastStateChannelSnapshotHashesProof.show.take(8)},o=${op.lastStateChannelSnapshotHashesProof.show.take(8)})"
                ),
                Option.when(lp.lastTxRefsProof =!= op.lastTxRefsProof)(
                  s"txRefsProof(l=${lp.lastTxRefsProof.show.take(8)},o=${op.lastTxRefsProof.show.take(8)})"
                ),
                Option.when(lp.balancesProof =!= op.balancesProof)(
                  s"balancesProof(l=${lp.balancesProof.show.take(8)},o=${op.balancesProof.show.take(8)})"
                ),
                Option.when(lp.lastCurrencySnapshotsProof =!= op.lastCurrencySnapshotsProof)("currencySnapshotsProof"),
                Option.when(lp.activeAllowSpends =!= op.activeAllowSpends)(
                  s"activeAllowSpends(l=${lp.activeAllowSpends.map(_.show.take(8))},o=${op.activeAllowSpends.map(_.show.take(8))})"
                ),
                Option.when(lp.activeTokenLocks =!= op.activeTokenLocks)(
                  s"activeTokenLocks(l=${lp.activeTokenLocks.map(_.show.take(8))},o=${op.activeTokenLocks.map(_.show.take(8))})"
                ),
                Option.when(lp.tokenLockBalances =!= op.tokenLockBalances)(
                  s"tokenLockBalances(l=${lp.tokenLockBalances.map(_.show.take(8))},o=${op.tokenLockBalances.map(_.show.take(8))})"
                ),
                Option.when(lp.lastAllowSpendRefs =!= op.lastAllowSpendRefs)(
                  s"lastAllowSpendRefs(l=${lp.lastAllowSpendRefs.map(_.show.take(8))},o=${op.lastAllowSpendRefs.map(_.show.take(8))})"
                ),
                Option.when(lp.lastTokenLockRefs =!= op.lastTokenLockRefs)(
                  s"lastTokenLockRefs(l=${lp.lastTokenLockRefs.map(_.show.take(8))},o=${op.lastTokenLockRefs.map(_.show.take(8))})"
                ),
                Option.when(lp.updateNodeParameters =!= op.updateNodeParameters)(
                  s"updateNodeParams(l=${lp.updateNodeParameters.map(_.show.take(8))},o=${op.updateNodeParameters.map(_.show.take(8))})"
                ),
                Option.when(lp.activeDelegatedStakes =!= op.activeDelegatedStakes)(
                  s"activeDelegatedStakes(l=${lp.activeDelegatedStakes.map(_.show.take(8))},o=${op.activeDelegatedStakes.map(_.show.take(8))})"
                ),
                Option.when(lp.delegatedStakesWithdrawals =!= op.delegatedStakesWithdrawals)(
                  s"delegatedStakesWithdrawals(l=${lp.delegatedStakesWithdrawals.map(_.show.take(8))},o=${op.delegatedStakesWithdrawals
                      .map(_.show.take(8))})"
                ),
                Option.when(lp.activeNodeCollaterals =!= op.activeNodeCollaterals)(
                  s"activeNodeCollaterals(l=${lp.activeNodeCollaterals.map(_.show.take(8))},o=${op.activeNodeCollaterals.map(_.show.take(8))})"
                ),
                Option.when(lp.nodeCollateralWithdrawals =!= op.nodeCollateralWithdrawals)(
                  s"nodeCollateralWithdrawals(l=${lp.nodeCollateralWithdrawals.map(_.show.take(8))},o=${op.nodeCollateralWithdrawals
                      .map(_.show.take(8))})"
                ),
                Option.when(lp.priceState =!= op.priceState)(
                  s"priceState(l=${lp.priceState.map(_.show.take(8))},o=${op.priceState.map(_.show.take(8))})"
                ),
                Option.when(lp.lastGlobalSnapshotsWithCurrency =!= op.lastGlobalSnapshotsWithCurrency)(
                  s"lastGlobalSnapshotsWithCurrency(l=${lp.lastGlobalSnapshotsWithCurrency
                      .map(_.show.take(8))},o=${op.lastGlobalSnapshotsWithCurrency.map(_.show.take(8))})"
                ),
                Option.when(lp.mptRoot =!= op.mptRoot)(
                  s"mptRoot(l=${lp.mptRoot.map(_.show.take(8))},o=${op.mptRoot.map(_.show.take(8))})"
                )
              ).flatten
              List(
                if (spDiffs.isEmpty) "stateProofDiffers(no sub-field diff — possible serialization difference)"
                else s"stateProofDiffers{${spDiffs.mkString(",")}}"
              )
            }
          val diffs: List[String] = List(
            Option.when(leader.ordinal =!= own.ordinal)(s"ordinal(leader=${leader.ordinal.show},own=${own.ordinal.show})"),
            Option.when(leader.height =!= own.height)(s"height(leader=${leader.height.show},own=${own.height.show})"),
            Option.when(leader.subHeight =!= own.subHeight)(s"subHeight(leader=${leader.subHeight.show},own=${own.subHeight.show})"),
            Option.when(leader.lastSnapshotHash =!= own.lastSnapshotHash)(
              s"lastSnapshotHash(leader=${leader.lastSnapshotHash.show.take(8)},own=${own.lastSnapshotHash.show.take(8)})"
            ),
            Option.when(leader.blocks.size != own.blocks.size)(s"blocks(leader=${leader.blocks.size},own=${own.blocks.size})"),
            Option.when(leader.stateChannelSnapshots.size != own.stateChannelSnapshots.size)(
              s"stateChannels(leader=${leader.stateChannelSnapshots.size},own=${own.stateChannelSnapshots.size})"
            ),
            Option.when(onlyLeader.nonEmpty)(s"scOnlyInLeader=[${onlyLeader.toList.map(_.show.take(8)).mkString(",")}]"),
            Option.when(onlyOwn.nonEmpty)(s"scOnlyInOwn=[${onlyOwn.toList.map(_.show.take(8)).mkString(",")}]")
          ).flatten ++ rewardsDiff ++ List(
            Option.when(leader.epochProgress =!= own.epochProgress)(
              s"epochProgress(leader=${leader.epochProgress.show},own=${own.epochProgress.show})"
            ),
            Option.when(leader.tips =!= own.tips)("tipsDiffer")
          ).flatten ++ stateProofDiff ++ peerHistoryDiffs(leader.peerHistory, own.peerHistory) ++ List(
            Option.when(leader.nextFacilitators =!= own.nextFacilitators)(
              s"nextFacilitators(leader=${leader.nextFacilitators.size},own=${own.nextFacilitators.size})"
            ),
            Option.when(leader.delegateRewards =!= own.delegateRewards)(
              s"delegateRewards(leader=${leader.delegateRewards.map(_.size).getOrElse(0)},own=${own.delegateRewards.map(_.size).getOrElse(0)})"
            ),
            Option.when(leader.allowSpendBlocks.map(_.size).getOrElse(0) != own.allowSpendBlocks.map(_.size).getOrElse(0))(
              s"allowSpendBlocks(leader=${leader.allowSpendBlocks.map(_.size).getOrElse(0)},own=${own.allowSpendBlocks.map(_.size).getOrElse(0)})"
            ),
            Option.when(leader.tokenLockBlocks.map(_.size).getOrElse(0) != own.tokenLockBlocks.map(_.size).getOrElse(0))(
              s"tokenLockBlocks(leader=${leader.tokenLockBlocks.map(_.size).getOrElse(0)},own=${own.tokenLockBlocks.map(_.size).getOrElse(0)})"
            ),
            Option.when(leader.spendActions =!= own.spendActions)(
              s"spendActions(leader=${leader.spendActions.map(_.size).getOrElse(0)},own=${own.spendActions.map(_.size).getOrElse(0)})"
            ),
            Option.when(leader.activeDelegatedStakes =!= own.activeDelegatedStakes)(
              s"activeDelegatedStakes(leader=${leader.activeDelegatedStakes.map(_.size).getOrElse(0)},own=${own.activeDelegatedStakes.map(_.size).getOrElse(0)})"
            ),
            Option.when(leader.delegatedStakesWithdrawals =!= own.delegatedStakesWithdrawals)(
              s"delegatedStakesWithdrawals(leader=${leader.delegatedStakesWithdrawals.map(_.size).getOrElse(0)},own=${own.delegatedStakesWithdrawals.map(_.size).getOrElse(0)})"
            ),
            Option.when(leader.activeNodeCollaterals =!= own.activeNodeCollaterals)(
              s"activeNodeCollaterals(leader=${leader.activeNodeCollaterals.map(_.size).getOrElse(0)},own=${own.activeNodeCollaterals.map(_.size).getOrElse(0)})"
            ),
            Option.when(leader.nodeCollateralWithdrawals =!= own.nodeCollateralWithdrawals)(
              s"nodeCollateralWithdrawals(leader=${leader.nodeCollateralWithdrawals.map(_.size).getOrElse(0)},own=${own.nodeCollateralWithdrawals.map(_.size).getOrElse(0)})"
            ),
            Option.when(leader.updateNodeParameters =!= own.updateNodeParameters)(
              s"updateNodeParameters(leader=${leader.updateNodeParameters.map(_.size).getOrElse(0)},own=${own.updateNodeParameters.map(_.size).getOrElse(0)})"
            ),
            Option.when(leader.version =!= own.version)(s"version(leader=${leader.version.show},own=${own.version.show})")
          ).flatten
          if (diffs.isEmpty) "GlobalArtifactMismatch(no field-level diff detected — possible serialization difference)"
          else s"GlobalArtifactMismatch[${diffs.mkString(",")}]"
        case other =>
          other.getClass.getSimpleName
      }

      private def peerHistoryDiffs(
        leader: Option[ConsensusOperationalState],
        own: Option[ConsensusOperationalState]
      ): List[String] =
        List(
          Option.when(leader.isDefined != own.isDefined)(
            s"peerHistory.present(leader=${leader.isDefined},own=${own.isDefined})"
          ),
          Option.when(leader.map(_.perPeer) =!= own.map(_.perPeer))("peerHistory.perPeerDiffer"),
          Option.when(leader.flatMap(_.recentSigners) =!= own.flatMap(_.recentSigners))("peerHistory.recentSignersDiffer"),
          Option.when(leader.map(_.recentProofSizes) =!= own.map(_.recentProofSizes))("peerHistory.recentProofSizesDiffer"),
          Option.when(leader.flatMap(_.recentRoundEndTimes) =!= own.flatMap(_.recentRoundEndTimes))(
            "peerHistory.recentRoundEndTimesDiffer"
          ),
          Option.when(leader.flatMap(_.controllerEvidence) =!= own.flatMap(_.controllerEvidence))(
            "peerHistory.controllerEvidenceDiffer"
          ),
          Option.when(leader.flatMap(_.penaltyUntil) =!= own.flatMap(_.penaltyUntil))("peerHistory.penaltyUntilDiffer")
        ).flatten ++ peerHistoryPerPeerDiff(leader, own)

      private def peerHistoryPerPeerDiff(
        leader: Option[ConsensusOperationalState],
        own: Option[ConsensusOperationalState]
      ): List[String] = {
        val leaderPerPeer: SortedMap[PeerId, PerPeerOperationalRecord] =
          leader.map(_.perPeer).getOrElse(SortedMap.empty[PeerId, PerPeerOperationalRecord])
        val ownPerPeer: SortedMap[PeerId, PerPeerOperationalRecord] =
          own.map(_.perPeer).getOrElse(SortedMap.empty[PeerId, PerPeerOperationalRecord])

        if (leaderPerPeer === ownPerPeer) Nil
        else {
          val leaderKeys = leaderPerPeer.keySet
          val ownKeys = ownPerPeer.keySet
          val onlyLeader = (leaderKeys -- ownKeys).toList.sorted
          val onlyOwn = (ownKeys -- leaderKeys).toList.sorted
          val valueDiffs = leaderKeys.intersect(ownKeys).toList.sorted.filter(pid => leaderPerPeer.get(pid) =!= ownPerPeer.get(pid))

          List(
            Some(
              s"peerHistory.perPeerDetail(leaderKeys=${leaderKeys.size},ownKeys=${ownKeys.size},onlyLeader=${onlyLeader.size},onlyOwn=${onlyOwn.size},valueDiffs=${valueDiffs.size})"
            ),
            Option.when(onlyLeader.nonEmpty)(s"peerHistory.perPeerOnlyLeader=[${compactPeerIds(onlyLeader)}]"),
            Option.when(onlyOwn.nonEmpty)(s"peerHistory.perPeerOnlyOwn=[${compactPeerIds(onlyOwn)}]"),
            Option.when(valueDiffs.nonEmpty)(s"peerHistory.perPeerValueDiffs=[${compactPeerIds(valueDiffs)}]")
          ).flatten
        }
      }

      private def compactPeerIds(peerIds: List[PeerId], limit: Int = 10): String = {
        val shown = peerIds.take(limit).map(_.show.take(8)).mkString(",")
        if (peerIds.size > limit) s"$shown,+${peerIds.size - limit}" else shown
      }

      /** Bounded artifact-mismatch diagnostics for the next failure. The existing field-level diff can report "no diff" when the semantic
        * fields compare equal but the canonical artifact hash still differs. These fields give us enough to decide whether the mismatch is
        * rooted in state proof roots, MPT/checkpoint state, collection cardinality, or canonical serialization/hash input without logging
        * the large state payload itself.
        */
      private def artifactMismatchDiagnostics(
        err: InvalidArtifact,
        leaderProposalHash: Hash,
        ownProposalHash: Hash
      ): F[Seq[(String, String)]] = err match {
        case GlobalArtifactMismatch(leader, own) =>
          (
            serializedArtifactDigest(leader),
            serializedArtifactDigest(own),
            artifactFieldDigests(leader),
            artifactFieldDigests(own)
          ).mapN {
            case ((leaderBytes, leaderSerializedHash), (ownBytes, ownSerializedHash), leaderFieldDigests, ownFieldDigests) =>
              Seq(
                "leaderProposalHash" -> leaderProposalHash.show.take(12),
                "ownProposalHash" -> ownProposalHash.show.take(12),
                "leaderSerializedBytes" -> leaderBytes,
                "ownSerializedBytes" -> ownBytes,
                "leaderSerializedHash" -> leaderSerializedHash,
                "ownSerializedHash" -> ownSerializedHash,
                "leaderArtifactDigest" -> artifactDigest(leader),
                "ownArtifactDigest" -> artifactDigest(own),
                "leaderFieldDigests" -> leaderFieldDigests,
                "ownFieldDigests" -> ownFieldDigests,
                "leaderStateProof" -> describeStateProof(leader.stateProof),
                "ownStateProof" -> describeStateProof(own.stateProof)
              )
          }
        case _ =>
          Seq.empty[(String, String)].pure[F]
      }

      private def serializedArtifactDigest(artifact: GlobalIncrementalSnapshot): F[(String, String)] =
        JsonSerializer[F]
          .serialize(artifact)
          .flatMap(bytes => Hash.fromBytesForSync[F](bytes).map(hash => (bytes.length.toString, hash.show.take(12))))
          .handleError(e => (s"error:${e.getClass.getSimpleName}", "unavailable"))

      private def serializedFieldDigest[A: Encoder](value: A): F[String] =
        JsonSerializer[F]
          .serialize(value)
          .flatMap(bytes => Hash.fromBytesForSync[F](bytes).map(hash => s"${bytes.length}/${hash.show.take(12)}"))
          .handleError(e => s"error:${e.getClass.getSimpleName}")

      private def artifactFieldDigests(artifact: GlobalIncrementalSnapshot): F[String] =
        List(
          "ordinal" -> serializedFieldDigest(artifact.ordinal),
          "height" -> serializedFieldDigest(artifact.height),
          "subHeight" -> serializedFieldDigest(artifact.subHeight),
          "lastSnapshotHash" -> serializedFieldDigest(artifact.lastSnapshotHash),
          "blocks" -> serializedFieldDigest(artifact.blocks),
          "stateChannelSnapshots" -> serializedFieldDigest(artifact.stateChannelSnapshots),
          "rewards" -> serializedFieldDigest(artifact.rewards),
          "delegateRewards" -> serializedFieldDigest(artifact.delegateRewards),
          "epochProgress" -> serializedFieldDigest(artifact.epochProgress),
          "nextFacilitators" -> serializedFieldDigest(artifact.nextFacilitators),
          "tips" -> serializedFieldDigest(artifact.tips),
          "deprecatedTips" -> serializedFieldDigest(artifact.tips.deprecated),
          "activeTips" -> serializedFieldDigest(artifact.tips.remainedActive),
          "stateProof" -> serializedFieldDigest(artifact.stateProof),
          "allowSpendBlocks" -> serializedFieldDigest(artifact.allowSpendBlocks),
          "tokenLockBlocks" -> serializedFieldDigest(artifact.tokenLockBlocks),
          "spendActions" -> serializedFieldDigest(artifact.spendActions),
          "updateNodeParameters" -> serializedFieldDigest(artifact.updateNodeParameters),
          "artifacts" -> serializedFieldDigest(artifact.artifacts),
          "activeDelegatedStakes" -> serializedFieldDigest(artifact.activeDelegatedStakes),
          "delegatedStakesWithdrawals" -> serializedFieldDigest(artifact.delegatedStakesWithdrawals),
          "activeNodeCollaterals" -> serializedFieldDigest(artifact.activeNodeCollaterals),
          "nodeCollateralWithdrawals" -> serializedFieldDigest(artifact.nodeCollateralWithdrawals),
          "peerHistory" -> serializedFieldDigest(artifact.peerHistory),
          "peerHistory.perPeer" -> serializedFieldDigest(artifact.peerHistory.map(_.perPeer)),
          "peerHistory.recentSigners" -> serializedFieldDigest(artifact.peerHistory.flatMap(_.recentSigners)),
          "peerHistory.recentProofSizes" -> serializedFieldDigest(artifact.peerHistory.map(_.recentProofSizes)),
          "peerHistory.recentRoundEndTimes" -> serializedFieldDigest(artifact.peerHistory.flatMap(_.recentRoundEndTimes)),
          "peerHistory.controllerEvidence" -> serializedFieldDigest(artifact.peerHistory.flatMap(_.controllerEvidence)),
          "peerHistory.penaltyUntil" -> serializedFieldDigest(artifact.peerHistory.flatMap(_.penaltyUntil)),
          "version" -> serializedFieldDigest(artifact.version)
        ).traverse { case (name, digest) => digest.map(value => s"$name=$value") }.map(_.mkString(" "))

      private def artifactDigest(artifact: GlobalIncrementalSnapshot): String =
        List(
          s"ordinal=${artifact.ordinal.show}",
          s"height=${artifact.height.show}",
          s"subHeight=${artifact.subHeight.show}",
          s"lastSnapshotHash=${artifact.lastSnapshotHash.show.take(12)}",
          s"blocks=${artifact.blocks.size}",
          s"stateChannels=${artifact.stateChannelSnapshots.size}",
          s"rewards=${artifact.rewards.size}",
          s"deprecatedTips=${artifact.tips.deprecated.size}",
          s"activeTips=${artifact.tips.remainedActive.size}",
          s"nextFacilitators=${artifact.nextFacilitators.size}",
          s"delegateRewards=${artifact.delegateRewards.map(_.size).getOrElse(0)}",
          s"allowSpendBlocks=${artifact.allowSpendBlocks.fold(0)(_.size)}",
          s"tokenLockBlocks=${artifact.tokenLockBlocks.fold(0)(_.size)}",
          s"spendActions=${artifact.spendActions.map(_.size).getOrElse(0)}",
          s"activeDelegatedStakes=${artifact.activeDelegatedStakes.map(_.size).getOrElse(0)}",
          s"delegatedStakesWithdrawals=${artifact.delegatedStakesWithdrawals.map(_.size).getOrElse(0)}",
          s"activeNodeCollaterals=${artifact.activeNodeCollaterals.map(_.size).getOrElse(0)}",
          s"nodeCollateralWithdrawals=${artifact.nodeCollateralWithdrawals.map(_.size).getOrElse(0)}",
          s"updateNodeParameters=${artifact.updateNodeParameters.map(_.size).getOrElse(0)}",
          s"version=${artifact.version.show}"
        ).mkString(" ")

      /** Produces a compact representation of all stateProof sub-field hash prefixes for comparing leader vs follower. */
      private def describeStateProof(sp: GlobalSnapshotStateProof): String =
        List(
          s"scHashes=${sp.lastStateChannelSnapshotHashesProof.show.take(8)}",
          s"txRefs=${sp.lastTxRefsProof.show.take(8)}",
          s"balances=${sp.balancesProof.show.take(8)}",
          s"currSnapshotsProof=${sp.lastCurrencySnapshotsProof.map(_.show.take(8)).getOrElse("none")}",
          s"allowSpends=${sp.activeAllowSpends.map(_.show.take(8)).getOrElse("none")}",
          s"tokenLocks=${sp.activeTokenLocks.map(_.show.take(8)).getOrElse("none")}",
          s"tokenLockBal=${sp.tokenLockBalances.map(_.show.take(8)).getOrElse("none")}",
          s"allowSpendRefs=${sp.lastAllowSpendRefs.map(_.show.take(8)).getOrElse("none")}",
          s"tokenLockRefs=${sp.lastTokenLockRefs.map(_.show.take(8)).getOrElse("none")}",
          s"nodeParams=${sp.updateNodeParameters.map(_.show.take(8)).getOrElse("none")}",
          s"delegStakes=${sp.activeDelegatedStakes.map(_.show.take(8)).getOrElse("none")}",
          s"delegWithdrawals=${sp.delegatedStakesWithdrawals.map(_.show.take(8)).getOrElse("none")}",
          s"nodeCollaterals=${sp.activeNodeCollaterals.map(_.show.take(8)).getOrElse("none")}",
          s"collateralWithdrawals=${sp.nodeCollateralWithdrawals.map(_.show.take(8)).getOrElse("none")}",
          s"priceState=${sp.priceState.map(_.show.take(8)).getOrElse("none")}",
          s"globalSnapsWithCurrency=${sp.lastGlobalSnapshotsWithCurrency.map(_.show.take(8)).getOrElse("none")}",
          s"mptRoot=${sp.mptRoot.map(_.show.take(8)).getOrElse("none")}"
        ).mkString(" ")

      /** Produces a compact digest of GlobalSnapshotInfo field sizes/counts for diagnostic logging. Does NOT log actual data (state can be
        * 90MB+), only counts and hash prefixes of the stateProof.
        */
      private def contextDigest(ctx: GlobalSnapshotContext): String =
        List(
          s"scHashes=${ctx.lastStateChannelSnapshotHashes.size}",
          s"txRefs=${ctx.lastTxRefs.size}",
          s"balances=${ctx.balances.size}",
          s"currencySnapshots=${ctx.lastCurrencySnapshots.size}",
          s"currencyProofs=${ctx.lastCurrencySnapshotsProofs.size}",
          s"allowSpends=${ctx.activeAllowSpends.map(_.values.map(_.values.map(_.size).sum).sum).getOrElse(0)}",
          s"tokenLocks=${ctx.activeTokenLocks.map(_.values.map(_.size).sum).getOrElse(0)}",
          s"tokenLockBal=${ctx.tokenLockBalances.map(_.size).getOrElse(0)}",
          s"delegStakes=${ctx.activeDelegatedStakes.map(_.size).getOrElse(0)}",
          s"delegWithdrawals=${ctx.delegatedStakesWithdrawals.map(_.size).getOrElse(0)}",
          s"nodeCollaterals=${ctx.activeNodeCollaterals.map(_.size).getOrElse(0)}",
          s"collateralWithdrawals=${ctx.nodeCollateralWithdrawals.map(_.size).getOrElse(0)}",
          s"updateNodeParams=${ctx.updateNodeParameters.map(_.size).getOrElse(0)}",
          s"priceState=${ctx.priceState.map(_.size).getOrElse(0)}",
          s"metagraphSync=${ctx.metagraphSyncData.map(_.size).getOrElse(0)}"
        ).mkString(" ")

      private def buildSignatureTransition(
        state: GlobalSnapshotConsensusState,
        status: CollectingProposals,
        majorityInfo: ArtifactInfo[GlobalSnapshotArtifact, GlobalSnapshotContext],
        proposalHashes: List[Hash],
        leaderVcc: Option[ViewChangeCertificate] = None,
        leaderTimeoutCertificate: Option[TimeoutCertificate] = None,
        leaderEvictionCerts: List[EvictionCertificate] = List.empty,
        leaderAdmissionCerts: List[AdmissionCertificate] = List.empty,
        leaderObservedResponders: List[PeerId] = List.empty,
        leaderObservedSelfHealth: SortedMap[PeerId, SelfHealthHint] = SortedMap.empty
      )(implicit hasher: Hasher[F]): F[Option[Transition]] = {
        // B1 apply: on proposal acceptance, shrink this round's committee by the set of peers
        // carried in the leader's EvictionCertificates. Validation already verified quorum +
        // signatures + committee membership, so applying is safe and deterministic (same for
        // every honest node reading the same proposal).
        //
        // Bootstrap gate: during bootstrap, validateProposalEcs already rejected any non-empty
        // cert list, so `leaderEvictionCerts` is effectively always empty here while
        // isInBootstrap is true. We leave the filter unchanged — the guard is just defense in
        // depth in case a future refactor forgets the validation-time check.
        val evictedTargets: Set[PeerId] =
          if (isInBootstrap(state)) Set.empty
          else leaderEvictionCerts.map(_.targetPeer).toSet
        val postEvictionFacilitators =
          if (evictedTargets.isEmpty) state.facilitators
          else Facilitators(state.facilitators.value.filterNot(evictedTargets.contains))
        val postEvictionRemoved =
          if (evictedTargets.isEmpty) state.removedFacilitators
          else RemovedFacilitators(state.removedFacilitators.value ++ evictedTargets)
        // B2 apply: on proposal acceptance, stash the set of re-admission targets on the
        // round state so the outcome-extraction step (buildFinishedTransition) can clear
        // those peers from lastOutcome.readmissionCountdown. Unlike evictions, admissions
        // do not mutate this round's `facilitators` — the target is not in the current
        // committee (validateProposalAcs already enforced that). The effect is visible in
        // the NEXT round's state creation, where the cleared peer is no longer filtered
        // out of fullBase via readmissionCountdown.
        // Defense in depth: validateProposalAcs already rejected any proposal carrying more than
        // the cap (`acs_too_many`), so this selection is a no-op on every honest path. Applying
        // the SAME shared deterministic selection here guarantees that even if a future refactor
        // ever lets an over-cap proposal through validation, every node still admits the same
        // capped subset.
        val admissionSelection =
          AdmissionCertificateSelector.select(leaderAdmissionCerts, config.activeAdmissionMaxExpansionPerRound)
        val admittedTargets: Set[PeerId] =
          admissionSelection.kept.map(_.targetPeer).toSet
        val postAdmissionAdmitted =
          if (admittedTargets.isEmpty) state.admittedFacilitators
          else AdmittedFacilitators(state.admittedFacilitators.value ++ admittedTargets)
        val acceptedTimeoutVoters: SortedSet[PeerId] =
          leaderTimeoutCertificate
            .map(tc => SortedSet.from(tc.votes.toNonEmptyList.toList.map(_.proofs.head.id.toPeerId)))
            .getOrElse(SortedSet.empty[PeerId])
        for {
          facilitatorsHash <- postEvictionFacilitators.value.hash
          view = state.viewNumber.toLong
          localLock <- consensusStorage.getVoteLock(state.key)
          effectiveLockedQc = VoteLock.maxByView(
            localLock.flatMap(_.lockedQc),
            leaderVcc.flatMap(_.highestQcInVcc)
          )
          tryLock <- consensusStorage.tryLockVote(state.key, view, majorityInfo.hash, effectiveLockedQc)
          result <- tryLock match {
            case Left(rejection) =>
              ConsensusLog
                .warn(
                  logger,
                  Category.Validation,
                  state.key.show,
                  "n/a",
                  Event.WithdrawValidationFail,
                  "reason" -> s"vote_lock_rejected: ${rejection.message}",
                  "rejection" -> rejection.code,
                  "view" -> view.toString,
                  "hash" -> majorityInfo.hash.show.take(8)
                )
                .as(none[Transition])
            case Right(_) =>
              for {
                acceptedAt <- Async[F].monotonic
                // Sign the proposal artifact hash directly. The Signed[artifact] downstream expects proofs to
                // verify against the artifact hash (not a domain-widened canonical byte sequence); widening
                // breaks toFinishedPhase -> verifySignatureProof(hash, proof). Safety against double-signing
                // is enforced at the VoteLock gate above (tryLockVote), which already rejects a second vote
                // at the same (key, view) for a different hash.
                signature <- Signature.fromHash(keyPair.getPrivate, majorityInfo.hash)
                // Self-store our MajoritySignature into the local resources immediately. Mirrors
                // the Fix-2 self-store-of-Facility pattern (commit 82179f2ec) for the signature
                // phase. Without this, our own signature only enters `resources.signatures` via
                // the gossip round-trip through RumorHandler; if three other peers' signatures
                // cross quorum in ~1-3ms (the ord-10 fast-path race), our node
                // finalizes its own round without its own signature and drops off `lastSigners`
                // in the next round's state creator. The complementary defence is the
                // `signatureGracePeriod` in buildFinishedTransition that also catches late
                // peer signatures; self-store closes the local-race half deterministically
                // at zero added latency.
                selfMajoritySig = MajoritySignature(
                  signature,
                  facilitatorsHash,
                  state.lastOutcome.finished.snapshotHash,
                  view,
                  majorityInfo.hash
                )
                _ <- consensusStorage.addSignature(selfId, state.key, selfMajoritySig).void
                signatureEmittedAt <- Async[F].monotonic
                _ <- Metrics[F].recordTimeHistogram(
                  "dag_consensus_proposal_accept_to_signature_time",
                  signatureEmittedAt - acceptedAt
                )
                _ <- recordProposalAffinity(proposalHashes, status.proposalArtifactInfo.hash)
                // Round succeeded — discard the proposal savepoint so it won't be restored on the next ordinal
                _ <- proposalSavepointRef.set(none)
                _ <- ConsensusLog
                  .info(
                    logger,
                    Category.Phase,
                    state.key.show,
                    "n/a",
                    Event.Eviction,
                    "assembly" -> "evictions_applied",
                    "targets" -> evictedTargets.toList.map(ConsensusLog.pid).mkString(","),
                    "count" -> evictedTargets.size.toString
                  )
                  .whenA(evictedTargets.nonEmpty)
                _ <- ConsensusLog
                  .info(
                    logger,
                    Category.Phase,
                    state.key.show,
                    "n/a",
                    Event.Admission,
                    "assembly" -> "admissions_applied",
                    "targets" -> admittedTargets.toList.map(ConsensusLog.pid).mkString(","),
                    "count" -> admittedTargets.size.toString
                  )
                  .whenA(admittedTargets.nonEmpty)
                // Should never fire (validation rejects over-cap proposals); a hit means an
                // over-cap proposal slipped past validateProposalAcs.
                _ <- (ConsensusLog
                  .info(
                    logger,
                    Category.Phase,
                    state.key.show,
                    "n/a",
                    Event.Admission,
                    "stage" -> "apply_cap",
                    "kept" -> admissionSelection.kept.map(c => ConsensusLog.pid(c.targetPeer)).mkString(","),
                    "dropped" -> admissionSelection.dropped.map(c => ConsensusLog.pid(c.targetPeer)).mkString(",")
                  ) >> Metrics[F].incrementCounter("dag_consensus_admission_cert_capped_total"))
                  .whenA(admissionSelection.dropped.nonEmpty)
              } yield
                Transition(
                  newState = state.copy(
                    facilitators = postEvictionFacilitators,
                    removedFacilitators = postEvictionRemoved,
                    admittedFacilitators = postAdmissionAdmitted,
                    // Controller evidence stage 1: certificate-applied eviction targets only
                    // (removedFacilitators also carries facility-phase fork-evictions, which
                    // the cert-anchored controllerEvidence / penaltyUntil fields must exclude).
                    certifiedEvictionTargets = state.certifiedEvictionTargets ++ evictedTargets,
                    // v7 codex turn 2 fix #5: REPLACE on accept (not union). Each accepted
                    // proposal canonically replaces state.observedResponders. View-N's set
                    // does NOT bleed into view-N+1 accounting after an honest view change.
                    observedResponders = ObservedResponders(leaderObservedResponders.toSet),
                    // v15: REPLACE on accept, same rationale as observedResponders.
                    observedSelfHealth = ObservedSelfHealth(leaderObservedSelfHealth),
                    acceptedTimeoutCertificateVoters = acceptedTimeoutVoters,
                    status = CollectingSignatures(
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
      }

      /** Canonical byte encoding of the signing domain for MajoritySignature: `(key, view, proposalHash, facilitatorsHash)`. Deterministic
        * across nodes so that any node holding a gossiped MajoritySignature can verify the signer.
        */
      private def canonicalSignBytes(
        key: GlobalSnapshotKey,
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
      // COLLECTING SIGNATURES → FINISHED
      // =========================================================================

      /** Advances from Signatures to Finished once quorum valid signatures are collected.
        *
        * Collects signature declarations, verifies each against the artifact hash, and transitions to Finished with the signed artifact.
        * Uses the artifact hash (not signed-artifact hash) as `snapshotHash` to avoid non-determinism from varying signature counts across
        * peers.
        */
      private def advanceFromSignatures(
        state: GlobalSnapshotConsensusState,
        status: CollectingSignatures,
        resources: ConsensusResources[GlobalSnapshotArtifact, GlobalConsensusKind]
      ): F[Option[Transition]] =
        loggerBundle.app.withOrdinal(status.majorityArtifactInfo.artifact.ordinal) {
          HasherSelector[F].withCurrent { implicit hasher =>
            for {
              maybeSignatures <- maybeGetAllDeclarations(state, resources)(_.signature)
              facilitators = maybeSignatures.map(_.keys.toList).getOrElse(List.empty[PeerId])
              _ <- loggerBundle.consensus.collectingSignatures(facilitators)
              // Skip facilitatorsHash fork check when view > 0 (eviction happened), solo→multi transition,
              // or during joining grace period (peer quality scores haven't converged yet).
              lastSolo2 <- wasLastRoundSolo
              inGrace2 <- nodeStorage.isInJoiningGracePeriod
              _ <- maybeSignatures
                .traverse_(
                  checkForkByFacilitatorsHash(_, status.facilitatorsHash, config.forkConfirmationMinObservations)(_.facilitatorsHash)
                )
                .whenA(!lastSolo2 && !inGrace2)
              _ <- maybeSignatures.traverse_(
                checkForkByLastSnapshotHash(_, status.lastSnapshotHash, config.forkConfirmationMinObservations)
              )
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
            Category.Phase,
            state.key.show,
            role,
            Event.SignaturesToFinished,
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
            // Finalization threshold -- two regimes (v4.1.0 cluster-majority floor).
            //
            // OUTSIDE bootstrap: finalization requires a super-majority of the FROZEN ROUND COMMITTEE
            // (`roundStartFacilitators`), via the SAME `QuorumDenominatorShrink.Decision.meets` that every
            // other cert/phase gate uses (`canFinalize` below). This closes the proven 2-of-5 fork: the
            // pre-v4.1.0 gate was a strict majority of the CORE sub-committee `(coreSize/2)+1`, which a Core
            // that had shrunk to a cluster-minority could satisfy and self-finalize a snapshot diverging
            // from the cluster majority. The Tier 1 reward-decoupling that motivated the Core-only gate
            // (alpha.88/89: 3 source nodes signing, 3 community Tier 1 silent, a Core+Tier1 threshold
            // unreachable) is now preserved differently: the round committee `roundStartFacilitators` is
            // already history- and admission-filtered, so genuinely-silent peers are dropped from it at the
            // next round-start -- the floor only requires a super-majority of the peers the consensus-agreed
            // derivation considered live this round, not of the raw cluster.
            //
            // IN bootstrap: the legacy strict-majority Core gate `(coreSize/2)+1` is preserved
            // byte-identical (plus the shrunk-path OR), keeping the deliberate cold-start liveness slack;
            // `clusterFloorActive(state)` (== !isInBootstrap) selects the regime.
            //
            // The grace-window machinery below (coreComplete / fullCommittee) is unchanged: it governs
            // reward-fair signature collection TIMING, not the finality threshold, so it stays Core/committee
            // -derived per its original design.
            //
            // The signature grace window is THREE-WAY, keyed on how complete the signer set is
            // (`coreComplete` + `fullCommittee`), so neither liveness nor reward fairness is
            // sacrificed:
            //
            //   1. FULL committee signed (`validSignatures.size >= fullCommittee`): finalize NOW.
            //      Nothing more can arrive, so any wait would be pure latency.
            //   2. CORE complete but not full: wait only the SHORT `tier1SignatureGracePeriod` for
            //      the remaining (Tier 1 / probation) signatures, then finalize. Liveness no longer
            //      needs the full window once Core has signed, but we still give Tier 1 signatures a
            //      brief, bounded chance to land so rewards are not collapsed onto Core. This
            //      replaces the alpha.153 "finalize the INSTANT Core completes" behavior, which
            //      dropped every Tier 1 signature from `signedArtifact.proofs` and collapsed the
            //      reward split to Core.
            //   3. CORE incomplete: wait the full `signatureGracePeriod` for the slow Core member,
            //      as before. This is the liveness-relevant case (a Core signature gates the quorum
            //      denominator), so it keeps the longer window.
            //
            // The short Tier-1 window keeps the alpha.152 fix intact (high-latency re-admitted peers
            // can no longer stall finalization for the FULL grace period -- the grace_wait_rate spike
            // and committee 3<->7 oscillation) while restoring Tier 1 reward inclusion. `fullCommittee`
            // now drives both the immediate-finalize check and the signature-count log display below.
            val canonicalCommitteeSize = state.roundStartFacilitators.value.size
            val coreSize = state.coreFacilitators.value.size
            val quorumThreshold = (coreSize / 2) + 1
            val fullCommittee = canonicalCommitteeSize
            val coreSet = state.coreFacilitators.value.toSet
            val coreSignedCount = validSignatures.count(p => coreSet.contains(p.id.toPeerId))
            val coreComplete = coreSignedCount >= coreSize
            val fullCommitteeSigned = validSignatures.size >= fullCommittee
            for {
              // v33 quorum-denominator shrink (QuorumDenominatorShrink): the finalization gate is the LAST
              // quorum chokepoint, routed through the same `Decision` as every cert/phase gate so it cannot
              // drift. v4.1.0: OUTSIDE bootstrap `canFinalize = decision.meets(signers)` -- signers.size must
              // reach the committee floor (`baseQuorum`), or, on the shrunk path, an anchor-majority that is
              // itself floored at the committee majority. A minority Core therefore cannot finalize on ANY
              // path. IN bootstrap the floor is off, so `meets` would reduce to the Core super-majority; to
              // keep cold start byte-identical we instead use the legacy strict-majority `(coreSize/2)+1`
              // with the shrunk-path OR exactly as before.
              shrinkDecision <- quorumFinalityDecision(state)
              shrinkSignerIds = validSignatures.map(_.id.toPeerId).toSet
              canFinalize =
                if (clusterFloorActive(state)) shrinkDecision.meets(shrinkSignerIds)
                else validSignatures.size >= quorumThreshold || shrinkDecision.shrunkPath(shrinkSignerIds)
              // Hash over canonical committee: this hash lands in Finished (and
              // thus lastOutcome.finished.facilitatorsHash), which fork detection
              // compares across peers. Deriving from state.facilitators (mutable)
              // would produce divergent hashes on nodes that observed a withdrawal
              // at different phases -- exactly the ord-5 fork trigger.
              facilitatorsHash <- state.roundStartFacilitators.value.hash
              facilitators = state.roundStartFacilitators.value
              _ <- loggerBundle.consensus.roundFinished(facilitators)
              // Require majority of facilitators to sign before completing the round.
              // Without this, a view-change minority (e.g., 2/5) can complete a round with a
              // different artifact than the majority (3/5), creating a fork that triggers
              // recovery download on the minority nodes.
              _ <- ConsensusLog
                .warn(
                  logger,
                  Category.Lifecycle,
                  state.key.show,
                  ConsensusLog.role(selfId, state.leader),
                  Event.RoundBlockedByState,
                  "reason" -> "insufficient_signatures",
                  "valid" -> validSignatures.size.toString,
                  "required" -> (if (clusterFloorActive(state)) shrinkDecision.baseQuorum else quorumThreshold).toString,
                  "committee" -> canonicalCommitteeSize.toString,
                  "facilitators" -> state.facilitators.value.size.toString
                )
                .whenA(!canFinalize)
              // Signature grace period (three-way, see the block comment above the threshold
              // derivation). If the full committee has signed, finalize immediately (nothing more
              // can arrive). Otherwise stamp the quorum-first-seen time and wait a BOUNDED grace:
              // the SHORT `tier1SignatureGracePeriod` when Core is already complete (collect late
              // Tier 1 / probation signatures for reward fairness without blocking liveness), or the
              // full `signatureGracePeriod` when a Core member is still missing (the
              // liveness-relevant case). Mirrors the pattern in `recoveryObserve` where we wait for
              // peer convergence before committing. Without any grace, a round that crosses quorum in
              // 1-3ms on a small cluster finalizes missing late signers -- noisy signer sets, missed
              // rewards, divergent peerQuality on downstream rounds.
              now <- Async[F].monotonic
              // Active grace window: short Tier-1 collection window once Core is complete, full
              // window while a Core signer is still missing. Used both by the wait evaluation below
              // and the diagnostic log line.
              activeGraceWindow = if (coreComplete) config.tier1SignatureGracePeriod else config.signatureGracePeriod
              // Three-way grace decision (see SignatureGraceDecision): full committee -> finalize now;
              // Core complete -> short Tier-1 window measured from FIRST Core completion (not first
              // quorum, so a late-completing Core still gets the full Tier-1 collection); Core
              // incomplete -> full window for the missing Core signer.
              quorumSeen <- signatureQuorumFirstSeenRef.modify { m =>
                val eval = SignatureGraceDecision.evaluate(
                  now = now,
                  validCount = validSignatures.size,
                  canFinalize = canFinalize,
                  fullCommitteeSigned = fullCommitteeSigned,
                  coreComplete = coreComplete,
                  existing = m.get(state.key),
                  tier1Window = config.tier1SignatureGracePeriod,
                  fullWindow = config.signatureGracePeriod
                )
                val m2 = eval.update match {
                  case SignatureGraceDecision.Leave  => m
                  case SignatureGraceDecision.Clear  => m - state.key
                  case SignatureGraceDecision.Set(s) => m + (state.key -> s)
                }
                (m2, eval)
              }
              firstQuorumCount = quorumSeen.firstQuorumCount
              firstObserved = quorumSeen.firstObserved
              waitMore = quorumSeen.waitMore
              graceElapsed = now - quorumSeen.graceStart
              _ <-
                if (canFinalize && firstObserved)
                  Metrics[F].incrementCounter("dag_consensus_signature_quorum_reached_total") >>
                    Metrics[F].recordDistribution("dag_consensus_signature_first_quorum_count", firstQuorumCount) >>
                    Metrics[F].recordDistribution("dag_consensus_signature_committee_size", fullCommittee) >>
                    Metrics[F].recordDistribution("dag_consensus_signature_required_count", quorumThreshold)
                else Applicative[F].unit
              _ <-
                if (waitMore)
                  Metrics[F].incrementCounter("dag_consensus_signature_grace_wait_total") >>
                    Metrics[F].recordTimeHistogram("dag_consensus_signature_grace_wait_time", graceElapsed) >>
                    Metrics[F].updateGauge("dag_consensus_signature_grace_current_valid_count", validSignatures.size.toLong) >>
                    Metrics[F].updateGauge("dag_consensus_signature_grace_committee_size", fullCommittee.toLong)
                else Applicative[F].unit
              _ <- ConsensusLog
                .debug(
                  logger,
                  Category.Phase,
                  state.key.show,
                  ConsensusLog.role(selfId, state.leader),
                  Event.SignaturesToFinished,
                  "grace" -> "waiting",
                  "signatures" -> s"${validSignatures.size}/$fullCommittee",
                  "required" -> quorumThreshold.toString,
                  "coreComplete" -> coreComplete.toString,
                  "gracePeriodMs" -> activeGraceWindow.toMillis.toString
                )
                .whenA(waitMore)
              result <-
                if (waitMore) none[Transition].pure[F]
                else if (canFinalize) {
                  val lateAdded = (validSignatures.size - firstQuorumCount).max(0)
                  signatureQuorumFirstSeenRef.update(_ - state.key) >>
                    Metrics[F].recordDistribution("dag_consensus_signature_final_count", validSignatures.size) >>
                    Metrics[F].recordDistribution("dag_consensus_signature_late_added_count", lateAdded) >>
                    Metrics[F].recordTimeHistogram("dag_consensus_signature_grace_final_wait_time", graceElapsed) >>
                    NonEmptySet.fromSet(validSignatures.toSortedSet).traverse { signaturesNes =>
                      val signedArtifact = Signed(status.majorityArtifactInfo.artifact, signaturesNes)
                      // Use the artifact hash (agreed upon during Proposals phase) instead of signedArtifact.hash.
                      // signedArtifact.hash includes signatures, which can differ across nodes when quorum < total
                      // (e.g., some nodes collect 3 signatures, others 4), causing non-deterministic snapshotHash
                      // and deadlocking the next round's Facility phase.
                      val snapshotHash = status.majorityArtifactInfo.hash
                      val nextPeerTiers = nextPeerTiersForFinished(state)
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
                        sideEffect = persistAndGossip(signedArtifact, status.majorityArtifactInfo.context) >>
                          recordPeerTierMetrics(state.lastOutcome.peerTiers, nextPeerTiers)
                      ).pure[F]
                    }
                } else {
                  none[Transition].pure[F]
                }
            } yield result
          }
        }

      // Hash over the canonical round-start committee. Used for Proposal declarations
      // and downstream facilitator-hash comparisons. Must be deterministic across all
      // nodes for a given round, so state.roundStartFacilitators (not the mutable set)
      // is the source.
      private def hashFacilitators(state: GlobalSnapshotConsensusState): F[Hash] =
        HasherSelector[F].withCurrent(implicit h => state.roundStartFacilitators.value.hash)

      private def hashArtifact(artifact: GlobalSnapshotArtifact): F[Hash] =
        HasherSelector[F].withCurrent(implicit h => artifact.hash)

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
              // Canonical round-start committee — matches validateLeaderArtifact's read so leader
              // and validators build/accept against the same facilitator set.
              state.roundStartFacilitators.value.toSet,
              getGlobalSnapshotByOrdinal,
              // v32 (stage 4): sign ONLY the deterministic chain-derived windows
              // (recentProofSizes, recentSigners, controllerEvidence, penaltyUntil).
              // perPeer / recentRoundEndTimes are locally divergent (the alpha.92/129/147
              // wedge class) and stay out of the signed bytes; see
              // GlobalConsensusOutcome.signedArtifactPeerHistory.
              Some(state.lastOutcome.signedArtifactPeerHistory)
            )
          }
        }

      private val selfId: PeerId = PeerId.fromPublic(keyPair.getPublic)

      /** Spread proposal — only called by the leader. Uses direct push to all facilitators. */
      private def spreadProposal(
        state: GlobalSnapshotConsensusState,
        key: GlobalSnapshotKey,
        hash: Hash,
        facilitatorsHash: Hash,
        artifact: GlobalSnapshotArtifact,
        lastSnapshotHash: Hash,
        view: Long = 0L,
        vcc: Option[ViewChangeCertificate] = None,
        timeoutCertificate: Option[TimeoutCertificate] = None,
        evictionCertificates: List[EvictionCertificate] = List.empty,
        admissionCertificates: List[AdmissionCertificate] = List.empty,
        observedResponders: List[PeerId] = List.empty,
        observedSelfHealth: SortedMap[PeerId, SelfHealthHint] = SortedMap.empty
      ): F[Unit] = {
        // Deterministic order is required — two leaders building from the same storage state
        // must produce the same proposal-hash payload, and `Set` iteration order is not guaranteed.
        val sortedEcs = evictionCertificates.sorted(EvictionCertificate.ordering)
        val sortedAcs = admissionCertificates.sorted(AdmissionCertificate.ordering)
        // observedResponders is sorted at the toProposalsPhase site; defensive re-sort here
        // ensures deterministic encoding regardless of caller path.
        val sortedObs = observedResponders.distinct.sorted
        val declaration =
          ConsensusPeerDeclaration(
            key,
            Proposal(
              hash = hash,
              facilitatorsHash = facilitatorsHash,
              lastSnapshotHash = lastSnapshotHash,
              view = view,
              vcc = vcc,
              timeoutCertificate = timeoutCertificate,
              evictionCertificates = sortedEcs,
              admissionCertificates = sortedAcs,
              observedResponders = sortedObs,
              observedSelfHealth = observedSelfHealth
            )
          )
        val targets = state.facilitators.value.toSet

        gossip.spreadDirect(declaration, targets) >>
          gossip.spreadCommon(ConsensusArtifact(key, artifact))
      }

      private def spreadSignature(
        state: GlobalSnapshotConsensusState,
        key: GlobalSnapshotKey,
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

      private def persistAndGossip(signedArtifact: Signed[GlobalIncrementalSnapshot], context: GlobalSnapshotContext): F[Unit] = {
        val persist = HasherSelector[F].withCurrent { implicit h =>
          for {
            hashed <- signedArtifact.toHashed
            _ <- lastNGlobalSnapshotStorage.set(hashed, context)
            _ <- lastGlobalSnapshotStorage.set(hashed, context)
            ok <- globalSnapshotStorage.prepend(signedArtifact, context)
          } yield ok
        }

        // Alpha.94: after a successful persist, write the post-finalization peerHistory sidecar.
        // `consensusStorage.getLastConsensusOutcome` returns the freshly-committed `Outcome[N]` since
        // `StateTransitions.tryUpdateLastConsensusOutcomeWithCleanup` has already run upstream.
        // `toOperationalState` produces the same ConsensusOperationalState the leader packed into the
        // SIGNED snapshot's `peerHistory` field at proposal time, except this one corresponds to N
        // rather than N-1. Best-effort -- write failures log and continue (the sidecar miss only
        // affects future rollback freshness; the persist itself has already succeeded).
        val writeSidecar: F[Unit] =
          consensusStorage.getLastConsensusOutcome.flatMap(_.traverse_ { outcome =>
            peerHistorySidecar.write(signedArtifact.value.ordinal, outcome.toOperationalState)
          })

        persist.ifM(
          clearCommittedEvents(signedArtifact.value) >> recordMetrics(signedArtifact) >> writeSidecar,
          ConsensusLog.error(logger, Category.Lifecycle, signedArtifact.ordinal.show, "n/a", Event.PersistFailed) >> MonadThrow[F]
            .raiseError(
              new RuntimeException("Persist failed")
            )
        )
      }

      private def clearCommittedEvents(artifact: GlobalSnapshotArtifact): F[Unit] = {
        val committed = committedEvents(artifact)

        if (committed.isEmpty) Applicative[F].unit
        else
          for {
            activeHashes <- eventMempool.getEventHashes
            activeEvents <- eventMempool.getMultiple(activeHashes)
            suspended <- eventMempool.suspendedSnapshot(Int.MaxValue)
            activeCommittedHashes = activeEvents.collect {
              case (hash, hashed) if committed.contains(hashed.signed.value) => hash
            }.toSet
            suspendedCommittedHashes = suspended.entries.collect {
              case (hash, entry) if committed.contains(entry.hashed.signed.value) => hash
            }.toSet
            committedHashes = activeCommittedHashes | suspendedCommittedHashes
            _ <- eventMempool.clearIncluded(committedHashes).whenA(committedHashes.nonEmpty)
            _ <- ConsensusLog
              .info(
                logger,
                Category.Lifecycle,
                artifact.ordinal.show,
                "n/a",
                Event.CommittedMempoolEventsCleared,
                "committedMempoolEventsCleared" -> committedHashes.size.toString
              )
              .whenA(committedHashes.nonEmpty)
          } yield ()
      }

      private def committedEvents(artifact: GlobalSnapshotArtifact): Set[GlobalSnapshotEvent] = {
        val dagEvents = artifact.blocks.unsorted.toList.map(_.block).map(DAGEvent(_))
        val scEvents = artifact.stateChannelSnapshots.toList.flatMap {
          case (address, stateChannelBinaries) => stateChannelBinaries.map(StateChannelOutput(address, _)).map(StateChannelEvent(_)).toList
        }
        val allowSpendEvents = artifact.allowSpendBlocks.toList.flatMap(_.toList.map(AllowSpendEvent(_)))
        val tokenLockEvents = artifact.tokenLockBlocks.toList.flatMap(_.toList.map(TokenLockEvent(_)))
        val unpEvents = artifact.updateNodeParameters.toList.flatMap(_.values.map(UpdateNodeParametersEvent(_)))
        val cdsEvents = artifact.activeDelegatedStakes.toList.flatMap(_.values.flatMap(_.map(CreateDelegatedStakeEvent(_))))
        val wdsEvents = artifact.delegatedStakesWithdrawals.toList.flatMap(_.values.flatMap(_.map(WithdrawDelegatedStakeEvent(_))))
        val cncEvents = artifact.activeNodeCollaterals.toList.flatMap(_.values.flatMap(_.map(CreateNodeCollateralEvent(_))))
        val wncEvents = artifact.nodeCollateralWithdrawals.toList.flatMap(_.values.flatMap(_.map(WithdrawNodeCollateralEvent(_))))

        (dagEvents ++ scEvents ++ allowSpendEvents ++ tokenLockEvents ++ unpEvents ++ cdsEvents ++ wdsEvents ++ cncEvents ++ wncEvents).toSet
      }

      private def checkForkByLastSnapshotHash[A](declarations: SortedMap[PeerId, A], ownHash: Hash, minObservations: Int)(
        implicit extract: A => Hash
      ): F[Unit] =
        recoverIfForking[F](
          ownHash,
          ConsensusStateUpdater.ForkObservation.LastSnapshotHash,
          nodeStorage,
          forkObservationsRef,
          config.forkConfirmationWindow,
          minObservations
        )(
          declarations.map { case (pid, decl) => (pid, extract(decl)) }
        )

      /** Skip facilitatorsHash fork check when transitioning from solo genesis (facilitators=1) to multi-node consensus. During solo
        * rounds, PeerQualityTracker penalty state diverges between genesis and downloading validators (it's node-local, not shared via
        * consensus). This causes different facilitatorsHash values on the first multi-node round.
        */
      private def wasLastRoundSolo: F[Boolean] =
        consensusStorage.getLastConsensusOutcome.map {
          case Some(outcome) => outcome.facilitators.value.size <= 1
          case None          => true // No previous round — genesis, treat as solo
        }

      private def checkForkByFacilitatorsHash[A](
        declarations: SortedMap[PeerId, A],
        ownHash: Hash,
        minObservations: Int
      )(extractHash: A => Hash): F[Unit] =
        recoverIfForking[F](
          ownHash,
          ConsensusStateUpdater.ForkObservation.FacilitatorsHash,
          nodeStorage,
          forkObservationsRef,
          config.forkConfirmationWindow,
          minObservations
        )(
          declarations.map { case (pid, decl) => (pid, extractHash(decl)) }
        )

      /** Detect mis-configured peers by comparing consensusConfigHash across Facility declarations.
        *
        * A node with different timing config (e.g. wrong CL_DECLARATION_TIMEOUT) will compute a different deterministicConfigHash. Unlike
        * facilitatorsHash (which hashes only peer IDs), this catches nodes that could participate in consensus rounds but silently diverge
        * on timing behaviour.
        */
      private def checkForkByConsensusConfigHash(declarations: SortedMap[PeerId, Facility]): F[Unit] = {
        // A `consensusConfigHash` divergence cannot be repaired by recovery
        // download (the local node's deterministicConfigHash is computed from local config and won't
        // change after rejoin). Loop-triggering recovery on this class of divergence is wasted I/O.
        // Surface it via `dag_consensus_unrepairable_mismatch` + structured log so operators can fix
        // the misconfigured peer; consensus continues but the divergence is visible.
        val ownConfigHash = config.deterministicConfigHash
        val peerHashes = declarations.collect { case (pid, f) => f.consensusConfigHash.map(pid -> _) }.flatten.toMap
        logRecoveryUnsuitableMismatch[F](
          ownConfigHash,
          ConsensusStateUpdater.ForkObservation.ConsensusConfigHash
        )(
          SortedMap.from(peerHashes)
        )
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

        Async[F].realTimeInstant.map(_.getEpochSecond.toDouble).flatMap { snapshotEpochSecond =>
          // Freshness timestamp: Grafana queries can use this to filter stale metrics.
          // A peer that stops advancing leaves its _ordinal gauge at the last accepted value
          // forever, making it look like it's still serving that ordinal. Emit a companion
          // "last updated" gauge so dashboards can show only peers refreshed within N seconds:
          //   dag_global_snapshot_ordinal and (time() - dag_global_snapshot_last_updated_epoch < 120)
          Metrics[F].updateGauge("dag_global_snapshot_last_updated_epoch", snapshotEpochSecond)
        } >>
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
