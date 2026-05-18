package io.constellationnetwork.node.shared.infrastructure.consensus.state

import cats.effect.kernel.{Async, Temporal}
import cats.effect.std.Random
import cats.syntax.all._
import cats.{Eq, Show}

import scala.concurrent.duration._

import io.constellationnetwork.node.shared.infrastructure.consensus.ConsensusLog.{Category, Event => LogEvent}
import io.constellationnetwork.node.shared.infrastructure.consensus.engine.ConsensusCommand._
import io.constellationnetwork.node.shared.infrastructure.consensus.engine._
import io.constellationnetwork.node.shared.infrastructure.consensus.message.GetConsensusOutcomeRequest
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger._
import io.constellationnetwork.node.shared.infrastructure.consensus.{ConsensusLog, ConsensusStorage}
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics.unsafeLabelName
import io.constellationnetwork.schema.node.NodeState
import io.constellationnetwork.schema.peer.{Peer, PeerId}
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed

import eu.timepit.refined.auto._
import monocle.Lens
import retry.RetryDetails
import retry.RetryPolicies.{constantDelay, limitRetries}
import retry.syntax.all._

/** Handles state transitions and lifecycle operations for consensus.
  *
  * ==Purpose==
  *
  * Contains the "business logic" for consensus state changes:
  *   - Checking for updates and advancing state
  *   - Finalizing outcomes and notifying FSM
  *   - Initialization and withdrawal
  *
  * ==Key Methods==
  *
  * '''checkUpdate(key):''' Called when new data arrives. Tries to update state and advance. If outcome is ready, calls finalizeAndNotify().
  * {{{
  *   checkUpdate(key)
  *       │
  *       ├── updater.tryUpdateConsensus(key, resources)
  *       │
  *       ├── advancer.getConsensusOutcome(newState)
  *       │     │
  *       │     ├── None → Wait for more data
  *       │     │
  *       │     └── Some((prevKey, outcome)) → finalizeAndNotify()
  *       │
  *       └── queue.offer(ConsensusFinished(...))
  * }}}
  *
  * '''finalizeAndNotify():''' Records metrics, updates storage, notifies FSM that consensus finished.
  *
  * '''initFromDownload(key, artifact, context):''' Fetches outcome from cluster peers, initializes storage, starts first round.
  *
  * '''initFromRollback(key, outcome):''' Sets outcome in storage, starts first round.
  *
  * '''withdraw():''' Spreads withdrawal declaration, cleans up state.
  *
  * '''registerPeer(peer):''' Registers newly observed peer for current consensus round.
  *
  * @see
  *   ConsensusStateUpdater for update logic
  * @see
  *   ConsensusStateAdvancer for advancement logic
  */
class StateTransitions[F[_]: Async: Random: Metrics, Event, Key: Eq: Show, Artifact: Eq, Ctx: Eq, Status, Outcome, Kind](
  ctx: ConsensusEngineContext[F, Event, Key, Artifact, Ctx, Status, Outcome, Kind]
)(
  implicit outcomeKey: Lens[Outcome, Key],
  outcomeArtifact: Lens[Outcome, Signed[Artifact]],
  outcomeContext: Lens[Outcome, Ctx],
  outcomeTrigger: Lens[Outcome, ConsensusTrigger]
) {

  import ctx.{advancer, config, facilitatorSelector, logger => log, peerQualityOf, peerQualityTracker, queue, remover, storage, updater}

  /** Deterministic witness pool for B1/B2/VCC certificate assembly.
    *
    * The pool unions two consensus-agreed sets, then removes `target`:
    *
    *   1. `state.eligibleFacilitators` -- peers eligible to facilitate THIS round (chronic-filtered subset of the previous outcome's
    *      participants). Always non-empty for active rounds. 2. Peers in `lastOutcome.peerQuality` with `participated >=
    *      minParticipationObservations` -- anyone who has actually voted in at least the observation-floor number of past rounds,
    *      regardless of whether they're currently in the chronic-excluded set.
    *
    * Determinism guarantees: both inputs are projections of `lastOutcome` which is signed and propagated as part of the previous snapshot.
    * `minParticipationObservations` is in `ConsensusConfig.deterministicConfigHash`. Every honest node therefore computes the
    * byte-identical witness pool from the same lastOutcome. The set semantics (Set[PeerId]) eliminates ordering as a determinism concern;
    * cert builders sort the resulting votes into a SortedSet so serialization is stable downstream.
    *
    * Why widen at all: in the canonical "committee = previous signers" pattern, when 4 of 6 committee members are offline or stuck in
    * `WaitingForDownload`, the round can't progress AND the eviction/admission cert that would normally rotate the committee also can't
    * assemble (same supermajority gate). Letting peers with proven prior participation witness the cert -- without giving them a vote in
    * the round itself -- breaks the deadlock without weakening the round's BFT guarantee. The wider pool only matters when there ARE peers
    * outside the committee with peerQuality history; in normal ops with a healthy committee it has no practical effect because the
    * committee dominates the union.
    *
    * Why this doesn't drift in steady state: peerQuality grows monotonically (entries are added, counters increment); it does not
    * arbitrarily reshape. The wider pool is therefore a monotone function of round history and consensus-agreed observations.
    *
    * Returns the EXCLUSIVE pool (target removed). Callers do not need to filter again.
    */
  private[state] def widerWitnessPool(state: ConsensusState[Key, Status, Outcome, Kind], target: PeerId): Set[PeerId] =
    WitnessPool.forTarget(
      state.eligibleFacilitators.value.toSet,
      peerQualityOf(state.lastOutcome),
      config.minParticipationObservations,
      target
    )

  /** Same as [[widerWitnessPool]] without target removal. Used for callers like VCC that aren't keyed by a specific target peer. */
  private[state] def widerWitnessPoolAll(state: ConsensusState[Key, Status, Outcome, Kind]): Set[PeerId] =
    WitnessPool.all(
      state.eligibleFacilitators.value.toSet,
      peerQualityOf(state.lastOutcome),
      config.minParticipationObservations
    )

  def checkUpdate(key: Key): F[Unit] =
    for {
      resources <- storage.getResources(key)
      maybeUpdate <- updater.tryUpdateConsensus(key, resources)
      _ <- maybeUpdate.traverse_ {
        case (_, newState) =>
          advancer
            .getConsensusOutcome(newState)
            .map { case (prevKey, outcome) => finalizeAndNotify(newState, prevKey, outcome) }
            .getOrElse(log.debug(ConsensusLog.format(Category.Phase, key.show, "n/a", LogEvent.StateUpdated)))
      }
    } yield ()

  /** Handle CheckViewChangeAssembly command.
    *
    * When a quorum of ViewChangeVotes has been collected for the current `(fromView, toView)` transition, assemble a valid VCC, store it so
    * the new leader's proposal path can embed it, deterministically pick the new leader, atomically advance
    * `state.viewNumber`/`state.leader`, reset the status to `CollectingFacilities` so the FSM re-enters phase 0 for the new view, and queue
    * `CheckUpdate` so the new leader's proposal flow fires.
    *
    * Safety against double-signing is enforced at the VoteLock gate during local signing, independent of how view transitions are driven.
    * This path is what makes the view transition itself consensus-certified.
    */
  def checkViewChangeAssembly(key: Key): F[Unit] =
    storage.getState(key).flatMap {
      case None => Async[F].unit
      case Some(state) =>
        val fromView = state.viewNumber.toLong
        val toView = fromView + 1L
        storage.getResources(key).flatMap { resources =>
          val votes = resources.viewChangeVotes.getOrElse((fromView, toView), Map.empty)
          val n = state.facilitators.value.size
          val q = math.max(1, math.ceil(n.toDouble * config.quorumThresholdFraction).toInt)
          if (votes.size >= q) {
            val facilitatorsHashCandidates = votes.values.map(_.value.facilitatorsHash).toSet
            facilitatorsHashCandidates.toList match {
              case singleHash :: Nil =>
                // Widen VCC witness pool to match EvictionCertificateBuilder's widening.
                // The proposal-validation path in the advancer derives the same pool
                // from the same consensus-agreed inputs, so this is the canonical pool for the
                // round. Quorum stays committee-sized (passed in q above).
                val vccPool = widerWitnessPoolAll(state)
                ViewChangeCertificateBuilder
                  .build(fromView, toView, singleHash, votes, q, vccPool) match {
                  case Left(error) =>
                    ConsensusLog.warn(
                      log,
                      Category.Phase,
                      key.show,
                      "n/a",
                      LogEvent.ViewChange,
                      "assembly" -> "vcc_build_failed",
                      "reason" -> error.code,
                      "fromView" -> fromView.toString,
                      "toView" -> toView.toString,
                      "votes" -> votes.size.toString,
                      "quorum" -> q.toString
                    )
                  case Right(vcc) =>
                    val newLeader =
                      facilitatorSelector.selectLeader(state.facilitators.value, state.entropy, toView.toInt)
                    val resetStatus = ctx.ops.freshCollectingFacilities(state.status)
                    val modify: ConsensusStorage.ModifyStateFn[F, Key, Status, Outcome, Kind, Boolean] =
                      new ConsensusStorage.ModifyStateFn[F, Key, Status, Outcome, Kind, Boolean] {
                        def apply(
                          maybeState: Option[ConsensusState[Key, Status, Outcome, Kind]]
                        ): F[Option[(Option[ConsensusState[Key, Status, Outcome, Kind]], Boolean)]] =
                          maybeState match {
                            case Some(s) if s.viewNumber === state.viewNumber =>
                              // Clear withdrawnFacilitators on view change. A withdrawal is scoped to
                              // the (key, view) pair at which it was emitted; a fresh view is logically
                              // a new commitment window. Without this reset, a view-0 withdrawal keeps
                              // `alreadyWithdrawn` tripping in all subsequent views and the node bails
                              // out of every proposal validation forever (the gl0-4 ord-3 stuck-for-11-min
                              // pattern). Peers that still want to withdraw in the new view will re-emit.
                              val updated: ConsensusState[Key, Status, Outcome, Kind] = resetStatus match {
                                case Some(fresh) =>
                                  s.copy(
                                    viewNumber = toView.toInt,
                                    leader = newLeader,
                                    status = fresh,
                                    withdrawnFacilitators = WithdrawnFacilitators.empty
                                  )
                                case None =>
                                  s.copy(
                                    viewNumber = toView.toInt,
                                    leader = newLeader,
                                    withdrawnFacilitators = WithdrawnFacilitators.empty
                                  )
                              }
                              (updated.some, true).some.pure[F]
                            case _ =>
                              none[(Option[ConsensusState[Key, Status, Outcome, Kind]], Boolean)].pure[F]
                          }
                      }
                    for {
                      _ <- storage.storeAssembledVcc(key, vcc)
                      advanced <- storage.condModifyState[Boolean](key)(modify)
                      didAdvance = advanced.getOrElse(false)
                      _ <- ConsensusLog
                        .info(
                          log,
                          Category.Phase,
                          key.show,
                          "n/a",
                          LogEvent.ViewChange,
                          "assembly" -> "quorum_reached_advanced",
                          "fromView" -> fromView.toString,
                          "toView" -> toView.toString,
                          "votes" -> votes.size.toString,
                          "quorum" -> q.toString,
                          "newLeader" -> ConsensusLog.pid(newLeader),
                          "statusReset" -> resetStatus.isDefined.toString
                        )
                        .whenA(didAdvance)
                      _ <- Metrics[F].updateGauge("dag_consensus_view_number", toView).whenA(didAdvance)
                      _ <- queue.offer(ConsensusCommand.CheckUpdate(key)).whenA(didAdvance)
                    } yield ()
                }
              case multiple =>
                ConsensusLog.warn(
                  log,
                  Category.Phase,
                  key.show,
                  "n/a",
                  LogEvent.ViewChange,
                  "assembly" -> "divergent_facilitators_hash",
                  "hashes" -> multiple.size.toString,
                  "fromView" -> fromView.toString,
                  "toView" -> toView.toString
                )
            }
          } else {
            log.debug(
              ConsensusLog.format(
                Category.Phase,
                key.show,
                "n/a",
                LogEvent.ViewChange,
                "assembly" -> "waiting_for_quorum",
                "votes" -> votes.size.toString,
                "quorum" -> q.toString
              )
            )
          }
        }
    }

  /** Handle `CheckEvictionAssembly(key, target)` command.
    *
    * Try to assemble an `EvictionCertificate` for the given target from the `EvictionVote`s collected so far in the round. Storage-side
    * accumulation is first-write-wins per (voter, target). If the votes reach quorum AND all agree on `facilitatorsHash`, build and store
    * the certificate so the next proposer can embed it in its Proposal.
    *
    * No side effects on `state.facilitators` or `state.removedFacilitators` from this path — those mutations happen at advancer
    * proposal-acceptance time (Phase 6 of the B1 rollout). Keeping certificate assembly and committee mutation on opposite sides of the
    * round boundary is what makes B1 safer than the mid-round eviction path the protocol deliberately removed.
    */
  def checkEvictionAssembly(key: Key, target: PeerId): F[Unit] =
    storage.getState(key).flatMap {
      case None                                                => Async[F].unit
      case Some(state) if ctx.isInBootstrap(state.lastOutcome) =>
        // Phase B1 gate: no certificate assembly during bootstrap. Even if eviction votes
        // arrived in storage (from peers running older/different logic), we refuse to build
        // a certificate until the cluster has produced a stable committee. Prevents cascading
        // splits observed in E2E.
        ConsensusLog.debug(
          log,
          Category.Phase,
          key.show,
          "n/a",
          LogEvent.Eviction,
          "assembly" -> "skipped_in_bootstrap",
          "target" -> ConsensusLog.pid(target)
        )
      case Some(state) =>
        storage.getResources(key).flatMap { resources =>
          val votes = resources.evictionVotes.getOrElse(target, Map.empty)
          // Quorum denominator MUST be the canonical round-start committee, not mutable
          // state.facilitators. The vote payloads hash roundStartFacilitators (see
          // GossipingEvictionVoter) and proposal validation also derives q from
          // roundStartFacilitators. Using state.facilitators here lets a node with a locally
          // shrunken committee (mid-round withdrawals) assemble an under-quorum cert that every
          // follower rejects — visible as locally-built but globally-invalid certs.
          val n = state.roundStartFacilitators.value.size
          val q = math.max(1, math.ceil(n.toDouble * config.quorumThresholdFraction).toInt)
          if (votes.size >= q) {
            // All votes for a given target must agree on facilitatorsHash; otherwise some
            // voter was signing against a different committee view and the certificate
            // would be invalid. Pick the hash with the most votes (tie: reject and wait).
            val byHash: Map[Hash, Int] =
              votes.values.groupBy(_.value.facilitatorsHash).view.mapValues(_.size).toMap
            byHash.toList.sortBy(-_._2) match {
              case (facHash, voteCount) :: _ if voteCount >= q =>
                // All votes with this hash share the same reason? For the current single-variant
                // Silent reason, this is trivially true. When more reasons land, select the
                // dominant (reason, hash) tuple similarly.
                val matchingVotes = votes.filter {
                  case (_, signed) => signed.value.facilitatorsHash == facHash
                }
                val reasons = matchingVotes.values.map(_.value.reason).toSet
                reasons.toList match {
                  case singleReason :: Nil =>
                    // Pool widens further to include `lastOutcome.peerQuality` peers
                    // (participated >= minParticipationObservations). The earlier widening to
                    // `eligibleFacilitators` did not cover the post-rollback wedge at ord 3122488 where
                    // the chronic-classifier excluded most non-source peers AND the committee was the
                    // entire eligibleFacilitators set. Adding historical participants -- peers consensus-
                    // agreed to have voted in past rounds -- preserves the supermajority quorum (still
                    // committee-sized) while allowing rotated-out peers with proven history to witness
                    // the cert. See `widerWitnessPool` for the full determinism analysis.
                    val witnessPool = widerWitnessPool(state, target)
                    val expectedLastSnap = ctx.lastSnapshotHashOf(state.lastOutcome)
                    EvictionCertificateBuilder.build(target, singleReason, facHash, expectedLastSnap, matchingVotes, q, witnessPool) match {
                      case Left(error) =>
                        ConsensusLog.warn(
                          log,
                          Category.Phase,
                          key.show,
                          "n/a",
                          LogEvent.Eviction,
                          "assembly" -> "ecs_build_failed",
                          "target" -> ConsensusLog.pid(target),
                          "reason" -> error.code,
                          "votes" -> matchingVotes.size.toString,
                          "quorum" -> q.toString
                        )
                      case Right(cert) =>
                        storage.storeAssembledEvictionCertificate(key, cert) >>
                          ConsensusLog.info(
                            log,
                            Category.Phase,
                            key.show,
                            "n/a",
                            LogEvent.Eviction,
                            "assembly" -> "quorum_reached_cert_stored",
                            "target" -> ConsensusLog.pid(target),
                            "votes" -> matchingVotes.size.toString,
                            "quorum" -> q.toString,
                            "reason" -> singleReason.toString
                          )
                    }
                  case multiReasons =>
                    ConsensusLog.warn(
                      log,
                      Category.Phase,
                      key.show,
                      "n/a",
                      LogEvent.Eviction,
                      "assembly" -> "divergent_reasons",
                      "target" -> ConsensusLog.pid(target),
                      "reasons" -> multiReasons.size.toString
                    )
                }
              case _ =>
                ConsensusLog.debug(
                  log,
                  Category.Phase,
                  key.show,
                  "n/a",
                  LogEvent.Eviction,
                  "assembly" -> "divergent_facilitators_hash",
                  "target" -> ConsensusLog.pid(target),
                  "hashes" -> byHash.size.toString
                )
            }
          } else {
            log.debug(
              ConsensusLog.format(
                Category.Phase,
                key.show,
                "n/a",
                LogEvent.Eviction,
                "assembly" -> "waiting_for_quorum",
                "target" -> ConsensusLog.pid(target),
                "votes" -> votes.size.toString,
                "quorum" -> q.toString
              )
            )
          }
        }
    }

  /** Handle `CheckAdmissionAssembly(key, target)` command. Mirrors [[checkEvictionAssembly]]. Attempts to assemble an
    * `AdmissionCertificate` for `target` once the `AdmissionVote` store holds at least quorum votes agreeing on `facilitatorsHash`.
    *
    * Like B1, certificate assembly is side-effect free for `state.facilitators` / `state.admittedFacilitators` — those mutations happen at
    * advancer proposal-acceptance time (Phase 6 of the B2 rollout).
    */
  def checkAdmissionAssembly(key: Key, target: PeerId): F[Unit] =
    storage.getState(key).flatMap {
      case None => Async[F].unit
      case Some(state) if ctx.isInBootstrap(state.lastOutcome) =>
        ConsensusLog.debug(
          log,
          Category.Phase,
          key.show,
          "n/a",
          LogEvent.Admission,
          "assembly" -> "skipped_in_bootstrap",
          "target" -> ConsensusLog.pid(target)
        )
      case Some(state) =>
        storage.getResources(key).flatMap { resources =>
          val votes = resources.admissionVotes.getOrElse(target, Map.empty)
          // See B1 eviction-cert assembly above for the rationale: quorum denominator must be
          // the canonical roundStartFacilitators, not mutable state.facilitators.
          val n = state.roundStartFacilitators.value.size
          val q = math.max(1, math.ceil(n.toDouble * config.quorumThresholdFraction).toInt)
          if (votes.size >= q) {
            val byHash: Map[Hash, Int] =
              votes.values.groupBy(_.value.facilitatorsHash).view.mapValues(_.size).toMap
            byHash.toList.sortBy(-_._2) match {
              case (facHash, voteCount) :: _ if voteCount >= q =>
                val matchingVotes = votes.filter { case (_, signed) => signed.value.facilitatorsHash == facHash }
                val reasons = matchingVotes.values.map(_.value.reason).toSet
                reasons.toList match {
                  case singleReason :: Nil =>
                    // Symmetric widening with B1 -- pool extended to include
                    // historical participants from `peerQuality` (see `widerWitnessPool` for the
                    // determinism analysis). Quorum stays committee-sized.
                    val witnessPool = widerWitnessPool(state, target)
                    val expectedLastSnap = ctx.lastSnapshotHashOf(state.lastOutcome)
                    AdmissionCertificateBuilder
                      .build(target, singleReason, facHash, expectedLastSnap, matchingVotes, q, witnessPool) match {
                      case Left(error) =>
                        ConsensusLog.warn(
                          log,
                          Category.Phase,
                          key.show,
                          "n/a",
                          LogEvent.Admission,
                          "assembly" -> "acs_build_failed",
                          "target" -> ConsensusLog.pid(target),
                          "reason" -> error.code,
                          "votes" -> matchingVotes.size.toString,
                          "quorum" -> q.toString
                        )
                      case Right(cert) =>
                        storage.storeAssembledAdmissionCertificate(key, cert) >>
                          ConsensusLog.info(
                            log,
                            Category.Phase,
                            key.show,
                            "n/a",
                            LogEvent.Admission,
                            "assembly" -> "quorum_reached_cert_stored",
                            "target" -> ConsensusLog.pid(target),
                            "votes" -> matchingVotes.size.toString,
                            "quorum" -> q.toString,
                            "reason" -> singleReason.toString
                          )
                    }
                  case multiReasons =>
                    ConsensusLog.warn(
                      log,
                      Category.Phase,
                      key.show,
                      "n/a",
                      LogEvent.Admission,
                      "assembly" -> "divergent_reasons",
                      "target" -> ConsensusLog.pid(target),
                      "reasons" -> multiReasons.size.toString
                    )
                }
              case _ =>
                ConsensusLog.debug(
                  log,
                  Category.Phase,
                  key.show,
                  "n/a",
                  LogEvent.Admission,
                  "assembly" -> "divergent_facilitators_hash",
                  "target" -> ConsensusLog.pid(target),
                  "hashes" -> byHash.size.toString
                )
            }
          } else {
            log.debug(
              ConsensusLog.format(
                Category.Phase,
                key.show,
                "n/a",
                LogEvent.Admission,
                "assembly" -> "waiting_for_quorum",
                "target" -> ConsensusLog.pid(target),
                "votes" -> votes.size.toString,
                "quorum" -> q.toString
              )
            )
          }
        }
    }

  private def finalizeAndNotify(
    newState: ConsensusState[Key, Status, Outcome, Kind],
    prevKey: Previous[Key],
    outcome: Outcome
  ): F[Unit] =
    for {
      now <- Async[F].monotonic
      duration = now - newState.createdAt
      _ <- Metrics[F].recordTime("dag_consensus_duration", duration)
      _ <- Metrics[F].recordTimeHistogram("dag_consensus_duration", duration)

      _ <- ctx.peerQualityTracker.recordRoundSuccess(newState.facilitators.value.toSet)
      leaderScore <- ctx.peerQualityTracker.getQualityScore(newState.leader)
      updated <- storage.tryUpdateLastConsensusOutcomeWithCleanup(prevKey, outcome)
      _ <- ctx.nodeStorage.decrementJoiningGracePeriod
      // Prune stale resources for keys other than the newly completed key.
      // This prevents memory growth from abandoned rounds leaving behind resource entries.
      activeKey = outcomeKey.get(outcome)
      _ <- storage.pruneStaleResources(activeKey)
      // Prune peer registrations from peers no longer in the cluster.
      // Peer registrations must be pruned to prevent stale departed-peer entries from
      // corrupting lagging detection in StallDetector (peersAtDifferentKey count).
      responsivePeers <- ctx.clusterStorage.getResponsivePeers
      activePeerIds = responsivePeers.map(_.id) + ctx.selfId
      _ <- storage.pruneStalePeerRegistrations(activePeerIds)
      _ <-
        if (updated) {
          val key = activeKey
          val trigger = outcomeTrigger.get(outcome)

          val withdrawnCount = newState.withdrawnFacilitators.value.size
          val removedCount = newState.removedFacilitators.value.size

          Metrics[F].incrementCounter(
            "dag_consensus_outcome_finalized",
            Seq(unsafeLabelName("trigger_type") -> trigger.toString)
          ) >>
            Metrics[F].incrementCounter(
              "dag_consensus_round_completed_total",
              Seq(
                unsafeLabelName("peer_id") -> ConsensusLog.pid(newState.leader),
                unsafeLabelName("trigger_type") -> trigger.toString
              )
            ) >>
            Metrics[F].updateGauge("dag_consensus_round_facilitator_count", newState.facilitators.value.size) >>
            Metrics[F].updateGauge("dag_consensus_round_eligible_count", newState.eligibleFacilitators.value.size) >> {
              // Diagnostic: include actual signers so we can compare across nodes. Different
              // nodes completing "same" ordinal with different signer sets is a fork — this
              // exposes it in logs rather than leaving it invisible.
              val signedArtifact = outcomeArtifact.get(outcome)
              val signerIds = signedArtifact.proofs.toList.map(p => ConsensusLog.pid(p.id.toPeerId)).sorted.mkString(",")
              val facilitatorIds = newState.facilitators.value.toList.map(ConsensusLog.pid).sorted.mkString(",")

              ConsensusLog.info(
                log,
                Category.Lifecycle,
                key.show,
                ConsensusLog.role(ctx.selfId, newState.leader),
                LogEvent.RoundCompleted,
                (Seq(
                  "trigger" -> trigger.toString,
                  "duration" -> s"${duration.toMillis}ms",
                  "facilitators" -> newState.facilitators.value.size.toString,
                  "facilitatorIds" -> facilitatorIds,
                  "signerCount" -> signedArtifact.proofs.size.toString,
                  "signerIds" -> signerIds,
                  "leader" -> ConsensusLog.pid(newState.leader),
                  "leaderScore" -> f"$leaderScore%.2f",
                  "view" -> newState.viewNumber.toString
                ) ++
                  (if (withdrawnCount > 0) Seq("withdrawn" -> withdrawnCount.toString) else Seq.empty) ++
                  (if (removedCount > 0) Seq("removed" -> removedCount.toString) else Seq.empty)): _*
              )
            } >> {
              // Per-peer observed_responders accounting: for each canonical committee member,
              // increment either `credited` (peer was in observedResponders, will get
              // completed+=1 in lastOutcome.peerQuality) or `omitted` (committee member that
              // missed observedResponders). The omission ratchet is what pushes a peer out of
              // the leader-rotation band over time. Label name `peer_id` matches the existing
              // dag_consensus_peer_quality_* family so a single Prometheus query joins them.
              // Skipped during bootstrap when observedResponders is empty.
              val responders: Set[PeerId] = newState.observedResponders.value.toSet
              val committee = newState.roundStartFacilitators.value
              if (responders.isEmpty || committee.isEmpty) Async[F].unit
              else {
                val peerIdLabel = unsafeLabelName("peer_id")
                committee.toList.traverse_ { pid =>
                  val labels: Metrics.TagSeq = Seq(peerIdLabel -> ConsensusLog.pid(pid))
                  if (responders.contains(pid))
                    Metrics[F].incrementCounter("dag_consensus_observed_responders_credited_total", labels)
                  else
                    Metrics[F].incrementCounter("dag_consensus_observed_responders_omitted_total", labels)
                }
              }
            } >>
            ctx.nodeStorage.tryModifyStateGetResult(NodeState.WaitingForReady, NodeState.Ready).void >>
            queue.offer(ConsensusFinished(key, outcome, trigger))
        } else {
          // OUTCOME_CONFLICT: another round completed first and stored its outcome.
          // Clean up the stale state and resources for this key to prevent memory leaks.
          // Without this cleanup, finished state entries accumulate in statesR/resourcesR
          // since cleanupStateAndResource only runs on the success path.
          storage.cleanupConflictedRound(activeKey) >>
            Metrics[F].incrementCounter("dag_consensus_outcome_conflict") >>
            ConsensusLog.warn(
              log,
              Category.Lifecycle,
              activeKey.show,
              "n/a",
              LogEvent.OutcomeConflict,
              "reason" -> "concurrent_finalization"
            )
        }
    } yield ()

  def registerPeer(peer: Peer): F[Unit] =
    storage.getLastConsensusOutcome.flatMap {
      case None => Async[F].unit
      case Some(outcome) =>
        storage.registerPeer(peer.id, outcomeKey.get(outcome)).handleError(_ => ())
    }

  def withdraw: F[Unit] =
    for {
      maybeOutcome <- storage.getLastConsensusOutcome
      _ <- maybeOutcome.traverse_ { outcome =>
        val key = outcomeKey.get(outcome)
        remover.withdrawFromConsensus(key)
      }
      _ <- storage.clearObservationKey
      _ <- ctx.nodeStorage.tryModifyState(NodeState.Observing, NodeState.Ready)
    } yield ()

  /** `dag_consensus_init_download_outcome_total{outcome}` - telemetry on which path through `initFromDownload` is exercised. `outcome`
    * labels: `success`, `self_in_probation` (B2 gate fired), `no_outcome_available` (fetchOutcomeFromCluster exhausted retries),
    * `outcome_validation_failed` (post-retry artifact/context mismatch), `storage_init_failed` (trySetInitialConsensusOutcome returned
    * false), `other` (anything else). Read alongside `dag_consensus_init_download_failure_tracked` and
    * `dag_consensus_force_leave_triggered` to identify why a recovering peer ends up in Leaving.
    */
  private def initDownloadOutcome(outcome: String): F[Unit] =
    Metrics[F].incrementCounter(
      "dag_consensus_init_download_outcome_total",
      Seq(unsafeLabelName("outcome") -> outcome)
    )

  private def classifyInitDownloadError(err: Throwable): String = err match {
    case _: SelfStillInProbation => "self_in_probation"
    case t =>
      val msg = Option(t.getMessage).getOrElse("")
      if (msg.startsWith("[DownloadInit] Could not observe outcome")) "no_outcome_available"
      else if (msg.startsWith("[DownloadInit] Outcome validation failed")) "outcome_validation_failed"
      else if (msg.contains("Failed to initialize consensus storage")) "storage_init_failed"
      else "other"
  }

  def initFromDownload(key: Key, artifact: Signed[Artifact], context: Ctx, isRecovery: Boolean = false): F[Unit] =
    (for {
      _ <- ConsensusLog.info(log, Category.Lifecycle, key.toString, "n/a", LogEvent.DownloadInitStart)
      // isRecoveryEffective = true if either the caller flagged this as recovery, OR the cluster
      // has advanced past our downloaded ordinal (peer returned a newer outcome). In both cases
      // we skip the 43s TimeTrigger deferral so the node joins the cluster immediately.
      (outcome, isRecoveryEffective) <- fetchOutcomeFromCluster(key, artifact, context, isRecovery)
        .flatMap(_.liftTo[F](new Throwable(s"[DownloadInit] Could not observe outcome for key=$key")))
        .flatMap { o =>
          // Explicit post-retry validation: retryingOnFailuresAndAllErrors returns the last value
          // when retries exhaust, even if wasSuccessful returned false for it. This guard prevents
          // silently accepting a mismatched outcome (wrong artifact/context) into consensus storage.
          val keyMatch = outcomeKey.get(o) === key
          val artifactMatch = outcomeArtifact.get(o) === artifact
          val contextMatch = outcomeContext.get(o) === context
          if (keyMatch && artifactMatch && contextMatch) (o, isRecovery).pure[F]
          else {
            // If the peer returned a DIFFERENT outcome (cluster has moved on past our downloaded
            // ordinal), accept it and treat as recovery — skip the 43s deferral so we join
            // the cluster at its current tip instead of targeting a stale ordinal.
            //
            // Lower-ordinal outcomes cannot reach here: ConsensusRoutes returns Conflict() when
            // the peer's key > requested key, and None when key doesn't match. Only the exact
            // key match returns Some(outcome). This branch is defensive against future API changes.
            val keyMismatch = outcomeKey.get(o) =!= key
            if (keyMismatch)
              (o, true).pure[F]
            else
              new Throwable(
                s"[DownloadInit] Outcome validation failed after retries for key=$key: " +
                  s"keyMatch=$keyMatch, artifactMatch=$artifactMatch, contextMatch=$contextMatch"
              ).raiseError[F, (Outcome, Boolean)]
          }
        }
      // B2 readmission gate: refuse to facilitate while self is on probation per the carried
      // outcome. A peer that was B1-evicted during isolation comes back
      // via recovery with a downloaded snapshot containing `readmissionCountdown[selfId] > 0`. The
      // cluster's state creator excludes probation peers from `state.facilitators`; if we
      // ignore that and emit Facility/Proposal/Signature anyway, our declarations land in nobody's
      // expected committee and the round wedges at `progress=1/5` until the whole 90s phase
      // timeout fires (gl0-4 fork-recovery E2E).
      //
      // Instead, raise `SelfStillInProbation` so the outer event-loop retry path re-issues
      // initFromDownload after backoff. The next attempt re-fetches the outcome from the cluster;
      // once the cluster has emitted a quorum-witnessed AdmissionCertificate clearing self from
      // probation, the check passes and recovery proceeds normally.
      _ <-
        if (ctx.probationPeersOf(outcome).contains(ctx.selfId)) {
          ConsensusLog
            .warn(
              log,
              Category.Lifecycle,
              key.toString,
              "n/a",
              LogEvent.DownloadInitStart,
              "gate" -> "self_in_readmission_probation",
              "action" -> "deferring_facilitation_until_b2_clears_probation"
            ) >> new SelfStillInProbation(ctx.selfId, key.toString)
            .raiseError[F, Unit]
        } else Async[F].unit
      // Mark recovery completion at this key. Layer-specific advancers consult this when self is
      // elected leader: if `state.key - recoveredAtKey <= recoveryLeaderCooldownRounds`, they
      // self-defer into a view change instead of attempting to propose. See
      // ConsensusEngineContext.recoveredAtKeyRef docstring for full rationale.
      _ <- ctx.recoveredAtKeyRef.set(Some(key))
      _ <- storage
        .trySetInitialConsensusOutcome(outcome)
        .ifM(
          ifFalse = new Throwable(s"[DownloadInit] Failed to initialize consensus storage").raiseError[F, Unit],
          ifTrue = ctx.nodeStorage.tryModifyState(NodeState.Observing, NodeState.WaitingForReady) >>
            ctx.nodeStorage.setJoiningGracePeriod >>
            ctx.nodeStorage.isValidatorMode.flatMap { isValidator =>
              if (isValidator && isRecoveryEffective) {
                // Validator recovery: start round immediately. The validator solo block
                // prevents solo production, and starting immediately avoids the 43s deferral
                // that caused ordinal mismatch deadlocks with the leader.
                ConsensusLog.info(
                  log,
                  Category.Lifecycle,
                  key.toString,
                  "n/a",
                  LogEvent.DownloadInitRecoveryImmediate,
                  "note" -> "Validator recovery: starting round immediately (solo blocked)"
                ) >>
                  queue.offer(StartRound(TimeTrigger.some))
              } else {
                // Initial join (all node types) or non-validator recovery: defer to align
                // with the cluster's TimeTrigger cadence. Without this delay on initial join,
                // validators form a majority without genesis, causing an irrecoverable split.
                ConsensusLog.info(
                  log,
                  Category.Lifecycle,
                  key.toString,
                  "n/a",
                  if (isRecoveryEffective) LogEvent.DownloadInitRecoveryDeferred else LogEvent.DownloadInitDeferred,
                  "deferral" -> s"${ctx.config.timeTriggerInterval.toSeconds}s"
                ) >>
                  Temporal[F].sleep(ctx.config.timeTriggerInterval) >>
                  queue.offer(StartRound(TimeTrigger.some))
              }
            }
        )
    } yield ())
      .flatTap(_ => initDownloadOutcome("success"))
      .onError { case err => initDownloadOutcome(classifyInitDownloadError(err)) }

  def initFromRollback(key: Key, outcome: Outcome): F[Unit] =
    for {
      _ <- ConsensusLog.info(log, Category.Lifecycle, key.toString, "n/a", LogEvent.RollbackInitStart)
      // Clear ALL stale consensus state before initializing from rollback.
      // Without this cleanup, peer registrations from the pre-rollback network survive
      // and contain keys higher than the rollback ordinal. The StallDetector then sees
      // the rollback node as "lagging behind network" (peersAtHigherKey > total/2) and
      // immediately abandons rounds → recovery download → 0 selectable peers → stuck.
      // This mirrors the cleanup done in AbandonmentTracker.attemptRecoveryDownload.
      _ <- storage.clearAllConsensusState
      _ <- storage.clearAllPeerRegistrations
      _ <- storage.clearTimeTrigger
      _ <- storage.clearObservationKey
      _ <- ctx.pending.clear()
      _ <- ConsensusLog.info(log, Category.Lifecycle, key.toString, "n/a", LogEvent.RollbackStateCleared)
      _ <- storage.trySetInitialConsensusOutcome(outcome)
      // Set joining grace period to use relaxed timeouts for first rounds after rollback.
      // Without this, the rollback node uses aggressive timeouts while peers are still
      // downloading to the rollback ordinal, leading to premature stall detection.
      _ <- ctx.nodeStorage.setJoiningGracePeriod
      // Start immediately after rollback. Rollback is a bootstrap operation — the node
      // may be genesis (first/only node) where solo consensus is correct and necessary.
      // Recovery after fork uses initFromDownload with isRecovery=true, which has its
      // own TimeTrigger deferral to prevent solo divergent snapshots.
      _ <- queue.offer(StartRound(TimeTrigger.some))
    } yield ()

  private def fetchOutcomeFromCluster(
    key: Key,
    artifact: Signed[Artifact],
    context: Ctx,
    isRecovery: Boolean = false
  ): F[Option[Outcome]] = {
    val retryPolicy = limitRetries(20).join(constantDelay(3.seconds))

    def selectPeer: F[Peer] =
      ctx.clusterStorage.getResponsivePeers.flatMap { allPeers =>
        // WaitingForReady peers hold a validated downloaded outcome (initFromDownload
        // already ran trySetInitialConsensusOutcome) and serve the same consensus
        // outcome endpoint as Ready peers. Including them prevents the post-rollback
        // bottleneck where only the rollback-lead node is Ready while sibling source
        // nodes sit in WaitingForReady waiting on a round to close: joining peers
        // funnel through the lone Ready peer and stall.
        val primaryCandidates =
          allPeers.filter(p => p.state == NodeState.Ready || p.state == NodeState.WaitingForReady).toSeq
        val observingPeers = allPeers.filter(_.state == NodeState.Observing).toSeq

        val candidates = if (primaryCandidates.nonEmpty) primaryCandidates else observingPeers

        if (candidates.isEmpty) {
          val peerStates = allPeers.toList.map(p => s"${ConsensusLog.pid(p.id)}=${p.state}").mkString(", ")
          ConsensusLog.warn(
            log,
            Category.Lifecycle,
            "n/a",
            "n/a",
            LogEvent.DownloadInitNoPeers,
            "peerStates" -> s"[$peerStates]"
          ) >>
            new NoValidPeersException(
              s"No peers in Ready, WaitingForReady, or Observing state. Available: ${allPeers.size} peers"
            ).raiseError[F, Peer]
        } else {
          Random[F].elementOf(candidates)
        }
      }

    def fetch(peer: Peer): F[Option[Outcome]] =
      ConsensusLog.debug(
        log,
        Category.Lifecycle,
        key.toString,
        "n/a",
        LogEvent.DownloadInitFetch,
        "peer" -> ConsensusLog.pid(peer.id),
        "state" -> peer.state.toString
      ) >>
        ctx.consensusClient
          .getSpecificConsensusOutcome(GetConsensusOutcomeRequest(key))
          .run(peer)
          .recoverWith {
            // 409 means the peer has already evicted this ordinal's outcome (cluster moved on).
            // Fall back to the latest available outcome so we can join at the current tip.
            case _: org.http4s.client.UnexpectedStatus =>
              ctx.consensusClient.getLatestConsensusOutcome.run(peer)
          }

    def wasSuccessful(maybeOutcome: Option[Outcome]): F[Boolean] =
      maybeOutcome.exists { outcome =>
        val exactMatch = outcomeKey.get(outcome) === key &&
          outcomeArtifact.get(outcome) === artifact &&
          outcomeContext.get(outcome) === context
        // During recovery, accept any valid outcome immediately. The cluster may have
        // advanced past our downloaded ordinal, so exact match will never succeed.
        // The post-retry validation in initFromDownload handles keyMismatch correctly
        // (accepts the newer outcome and skips 43s deferral). Without this early-out,
        // recovery wastes 60s (20 retries × 3s) on every cycle, falling further behind.
        exactMatch || isRecovery
      }.pure[F]

    def onFailure(maybeOutcome: Option[Outcome], retryDetails: RetryDetails): F[Unit] = {
      val attempt = retryDetails.retriesSoFar
      // Reduce noise: log every 5th attempt and the last attempt to avoid 20 nearly-identical lines
      if (attempt % 5 == 0 || attempt >= 19) {
        maybeOutcome.map { outcome =>
          val sameArtifact = outcomeArtifact.get(outcome) === artifact
          val sameContext = outcomeContext.get(outcome) === context
          ConsensusLog.info(
            log,
            Category.Lifecycle,
            key.show,
            "n/a",
            LogEvent.DownloadInitMismatch,
            "sameArtifact" -> sameArtifact.show,
            "sameContext" -> sameContext.show,
            "attempt" -> attempt.toString
          )
        }.getOrElse(
          ConsensusLog.info(
            log,
            Category.Lifecycle,
            key.show,
            "n/a",
            LogEvent.DownloadInitWaiting,
            "attempt" -> attempt.toString
          )
        )
      } else Async[F].unit
    }

    def onError(err: Throwable, retryDetails: RetryDetails): F[Unit] =
      log.error(err)(
        ConsensusLog.format(
          Category.Lifecycle,
          key.show,
          "n/a",
          LogEvent.DownloadInitError,
          "attempt" -> retryDetails.retriesSoFar.toString,
          "error" -> err.getMessage
        )
      )

    (selectPeer >>= fetch).retryingOnFailuresAndAllErrors(
      wasSuccessful = wasSuccessful,
      policy = retryPolicy,
      onFailure = onFailure,
      onError = onError
    )
  }

  class NoValidPeersException(message: String) extends RuntimeException(message)

  /** Raised when `initFromDownload` resolves an outcome that still lists `selfId` in `readmissionCountdown` (B2 probation). The outer
    * event-loop retry path catches this, backs off, and re-issues initFromDownload — by which time the cluster may have emitted an
    * `AdmissionCertificate` clearing the probation, allowing recovery to proceed.
    */
  class SelfStillInProbation(val selfId: PeerId, val keyShow: String)
      extends RuntimeException(
        s"[DownloadInit] self ${selfId.value.value.take(8)} still in B2 readmission probation at key=$keyShow; " +
          s"deferring facilitation until cluster clears probation."
      )
      with scala.util.control.NoStackTrace
}
