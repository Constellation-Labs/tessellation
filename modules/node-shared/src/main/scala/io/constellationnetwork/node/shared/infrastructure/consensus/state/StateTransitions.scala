package io.constellationnetwork.node.shared.infrastructure.consensus.state

import cats.effect.kernel.{Async, Temporal}
import cats.effect.std.Random
import cats.effect.syntax.all._
import cats.syntax.all._
import cats.{Eq, Show}

import scala.concurrent.duration._
import scala.reflect.runtime.universe.TypeTag

import io.constellationnetwork.node.shared.infrastructure.consensus.ConsensusLog.{Category, Event => LogEvent}
import io.constellationnetwork.node.shared.infrastructure.consensus._
import io.constellationnetwork.node.shared.infrastructure.consensus.declaration.TimeoutReason
import io.constellationnetwork.node.shared.infrastructure.consensus.engine.ConsensusCommand._
import io.constellationnetwork.node.shared.infrastructure.consensus.engine._
import io.constellationnetwork.node.shared.infrastructure.consensus.message.GetConsensusOutcomeRequest
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger._
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics.unsafeLabelName
import io.constellationnetwork.schema.node.NodeState
import io.constellationnetwork.schema.peer.{Peer, PeerId}
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed

import eu.timepit.refined.auto._
import io.circe.Encoder
import monocle.Lens
import org.http4s.client.UnexpectedStatus
import org.http4s.{Status => HttpStatus}
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
  * '''initFromRollback(key, outcome):''' Sets outcome in storage, then starts or defers the first round.
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
class StateTransitions[F[_]: Async: Random: Metrics, Event, Key: Eq: Show: TypeTag: Encoder, Artifact: Eq, Ctx: Eq, Status, Outcome, Kind](
  ctx: ConsensusEngineContext[F, Event, Key, Artifact, Ctx, Status, Outcome, Kind]
)(
  implicit outcomeKey: Lens[Outcome, Key],
  outcomeArtifact: Lens[Outcome, Signed[Artifact]],
  outcomeContext: Lens[Outcome, Ctx],
  outcomeTrigger: Lens[Outcome, ConsensusTrigger]
) {

  import ctx.{advancer, config, facilitatorSelector, gossip, logger => log, peerQualityOf, queue, remover, storage, updater}
  import io.constellationnetwork.node.shared.infrastructure.consensus.message.ConsensusAssembledVcc

  private def runOutcomeHook(stage: String, outcome: Outcome)(hook: Outcome => F[Unit]): F[Unit] =
    for {
      startedAt <- Async[F].monotonic
      result <- hook(outcome).attempt
      finishedAt <- Async[F].monotonic
      resultName = result.fold(_ => "failure", _ => "success")
      labels = Seq(unsafeLabelName("stage") -> stage, unsafeLabelName("outcome") -> resultName)
      _ <- Metrics[F].recordTimeHistogram("dag_consensus_outcome_hook", finishedAt - startedAt, labels)
      _ <- Metrics[F].incrementCounter("dag_consensus_outcome_sidecar_total", labels)
      _ <- result.fold(
        error => log.warn(error)(s"Best-effort consensus outcome hook failed at stage=$stage"),
        _ => Async[F].unit
      )
    } yield ()

  /** Deterministic witness pool for B1/B2/VCC certificate assembly.
    *
    * The pool unions consensus-agreed sets, then removes `target`:
    *
    *   1. `state.roundStartFacilitators` -- the frozen committee for this round. 2. `state.eligibleFacilitators` -- peers eligible to
    *      facilitate THIS round (chronic-filtered subset of the previous outcome's participants). Always non-empty for active rounds. 3.
    *      Peers in `lastOutcome.peerQuality` with `participated >= minParticipationObservations` -- anyone who has actually voted in at
    *      least the observation-floor number of past rounds, regardless of whether they're currently in the chronic-excluded set.
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
    WitnessPool
      .forTarget(
        state.eligibleFacilitators.value.toSet,
        peerQualityOf(state.lastOutcome),
        config.minParticipationObservations,
        target
      )
      .union(state.roundStartFacilitators.value.toSet - target)

  /** Same as [[widerWitnessPool]] without target removal. Used for callers like VCC that aren't keyed by a specific target peer. */
  private[state] def widerWitnessPoolAll(state: ConsensusState[Key, Status, Outcome, Kind]): Set[PeerId] =
    WitnessPool
      .all(
        state.eligibleFacilitators.value.toSet,
        peerQualityOf(state.lastOutcome),
        config.minParticipationObservations
      )
      .union(state.roundStartFacilitators.value.toSet)

  /** v33 quorum-denominator shrink decision for this round -- thin delegate to the single shared derivation on the advancer (see
    * `ConsensusStateAdvancer.quorumShrinkDecision` and the `QuorumDenominatorShrink` scaladoc for the determinism contract). Inert in
    * normal operation; consumed by the VCC/TC assembly and apply gates below.
    */
  private def quorumShrinkDecisionFor(
    state: ConsensusState[Key, Status, Outcome, Kind]
  ): F[QuorumDenominatorShrink.Decision] =
    advancer.quorumShrinkDecision(state)

  /** Select the one certificate-voter universe for the active schema.
    *
    * Legacy rounds retain the wider witness pool and optional shrink rung. V35 VCC/TC certificates are instead made only from uniquely
    * identified frozen-Core voters and require the same BFT quorum function as ProposalQC/CoreCommitQC. Keeping this selection generic in
    * the shared state machine prevents DAG and Currency -- and the VCC and TC paths -- from drifting onto different safety universes.
    */
  private def certificateQuorum[A](
    state: ConsensusState[Key, Status, Outcome, Kind],
    votes: Map[PeerId, Signed[A]],
    shrinkDecision: QuorumDenominatorShrink.Decision
  ): StateTransitions.CertificateQuorum[A] =
    if (state.certifiedConsensusActive) {
      val core = state.coreFacilitators.value.toSet
      val coreVotes = votes.collect {
        case (origin, signed)
            if signed.proofs.size === 1L &&
              signed.proofs.head.id.toPeerId === origin &&
              core.contains(origin) =>
          origin -> signed
      }
      val required = CertifiedConsensus.requiredCoreQuorum(core.size, config.quorumThresholdFraction)

      StateTransitions.CertificateQuorum(coreVotes, core, required, coreVotes.size >= required)
    } else {
      val required = shrinkDecision.builderQuorum(votes.keySet)
      StateTransitions.CertificateQuorum(votes, widerWitnessPoolAll(state), required, shrinkDecision.meets(votes.keySet))
    }

  /** Observability for a gate that passed only via the shrunken quorum margin: one INFO line + the rung-activation counter. */
  private def logQuorumShrinkApplied(
    key: Key,
    site: String,
    decision: QuorumDenominatorShrink.Decision,
    voters: Set[PeerId]
  ): F[Unit] =
    (
      ConsensusLog.info(
        log,
        Category.Phase,
        key.show,
        "n/a",
        LogEvent.ViewChange,
        "assembly" -> "quorum_shrink_applied",
        "site" -> site,
        "votes" -> voters.size.toString,
        "anchorVotes" -> voters.count(decision.anchor.contains).toString,
        "baseQuorum" -> decision.baseQuorum.toString,
        "requiredQuorum" -> decision.requiredQuorum.toString,
        "steps" -> decision.steps.toString,
        "anchorSize" -> decision.anchor.size.toString
      ) >>
        Metrics[F].incrementCounter(
          "dag_consensus_quorum_shrink_applied_total",
          Seq(unsafeLabelName("site") -> site)
        ) >>
        Metrics[F].updateGauge("dag_consensus_quorum_shrink_required", decision.requiredQuorum.toLong)
    ).whenA(decision.shrunkPath(voters))

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

  /** Attempt same-key convergence from a peer's fully certified v35 outcome before abandoning the round.
    *
    * The HTTP response and node-local sidecar are transport only. Every candidate is re-derived by the layer advancer against this node's
    * locally known parent and frozen committee, and all artifact/QC signatures are verified before state changes. Two independently valid
    * value hashes fail closed.
    */
  def tryAdoptCertifiedOutcome(key: Key): F[Boolean] =
    storage.getState(key).flatMap {
      case Some(state) if state.certifiedConsensusActive && advancer.getConsensusOutcome(state).isEmpty =>
        ctx.clusterStorage.getResponsivePeers.flatMap { peers =>
          val candidates = peers.iterator
            .filter(peer => peer.id =!= ctx.selfId && (peer.state === NodeState.Ready || peer.state === NodeState.WaitingForReady))
            .toList

          Random[F]
            .shuffleList(candidates)
            .map(_.take(StateTransitions.CertifiedRecoverySampleSize))
            .flatMap(
              _.parTraverseN(StateTransitions.CertifiedRecoveryParallelism) { peer =>
                ctx.consensusClient
                  .getSpecificConsensusOutcome(GetConsensusOutcomeRequest(key))
                  .run(peer)
                  .map(_.map(peer -> _))
                  .timeoutTo(StateTransitions.CertifiedRecoveryPerPeerTimeout, none[(Peer, Outcome)].pure[F])
                  .handleError(_ => none[(Peer, Outcome)])
              }
            )
            .map(_.flatten)
            .flatMap { fetched =>
              fetched.traverse {
                case (peer, candidate) =>
                  advancer.certifiedOutcomeAdoption(state, candidate).flatMap {
                    case Right(adoption) => (peer, candidate, adoption.valueHash).some.pure[F]
                    case Left(reason) =>
                      Metrics[F].incrementCounter(
                        "dag_consensus_certified_recovery_candidate_total",
                        Seq(unsafeLabelName("outcome") -> "rejected", unsafeLabelName("reason") -> reason)
                      ) >>
                        ConsensusLog
                          .debug(
                            log,
                            Category.Recovery,
                            key.show,
                            "n/a",
                            LogEvent.CertifiedOutcomeRecovery,
                            "outcome" -> "candidate_rejected",
                            "peer" -> ConsensusLog.pid(peer.id),
                            "reason" -> reason
                          )
                          .as(none[(Peer, Outcome, Hash)])
                  }
              }.flatMap { verifiedOptions =>
                val verified = verifiedOptions.flatten
                val selection = StateTransitions.selectCertifiedRecoveryCandidate(
                  verified.sortBy(_._1.id.value.value).map { case (peer, candidate, hash) => hash -> (peer -> candidate) }
                )

                selection match {
                  case Left(distinctHashes) =>
                    ConsensusLog
                      .error(
                        log,
                        Category.Fork,
                        key.show,
                        "n/a",
                        LogEvent.CertifiedOutcomeRecovery,
                        "outcome" -> "divergent_valid_certificates",
                        "valueHashes" -> distinctHashes.toString,
                        "candidates" -> verified.size.toString
                      ) >>
                      Metrics[F]
                        .incrementCounter(
                          "dag_consensus_certified_recovery_total",
                          Seq(unsafeLabelName("outcome") -> "divergent_valid_certificates")
                        )
                        .as(false)
                  case Right(None) =>
                    Metrics[F]
                      .incrementCounter(
                        "dag_consensus_certified_recovery_total",
                        Seq(unsafeLabelName("outcome") -> "no_valid_candidate")
                      )
                      .as(false)
                  case Right(Some((peer, candidate))) => adoptCertifiedOutcome(key, peer, candidate)
                }
              }
            }
        }
      case _ => false.pure[F]
    }

  private def adoptCertifiedOutcome(key: Key, source: Peer, candidate: Outcome): F[Boolean] = {
    type Adoption = (
      ConsensusState[Key, Status, Outcome, Kind],
      Previous[Key],
      Outcome,
      F[Unit]
    )

    val modify = new ConsensusStorage.ModifyStateFn[F, Key, Status, Outcome, Kind, Adoption] {
      def apply(
        maybeState: Option[ConsensusState[Key, Status, Outcome, Kind]]
      ): F[Option[(Option[ConsensusState[Key, Status, Outcome, Kind]], Adoption)]] =
        maybeState match {
          case Some(current) if current.certifiedConsensusActive && advancer.getConsensusOutcome(current).isEmpty =>
            advancer.certifiedOutcomeAdoption(current, candidate).map {
              case Right(adoption) =>
                advancer.getConsensusOutcome(adoption.state).map {
                  case (previous, outcome) =>
                    adoption.state.some -> (adoption.state, previous, outcome, adoption.sideEffect)
                }
              case Left(_) => none
            }
          case _ => none[(Option[ConsensusState[Key, Status, Outcome, Kind]], Adoption)].pure[F]
        }
    }

    storage.condModifyState[Adoption](key)(modify).flatMap {
      case Some((recoveredState, previous, outcome, sideEffect)) =>
        sideEffect >>
          finalizeAndNotify(recoveredState, previous, outcome) >>
          ConsensusLog.info(
            log,
            Category.Recovery,
            key.show,
            "n/a",
            LogEvent.CertifiedOutcomeRecovery,
            "outcome" -> "adopted",
            "peer" -> ConsensusLog.pid(source.id)
          ) >>
          Metrics[F]
            .incrementCounter(
              "dag_consensus_certified_recovery_total",
              Seq(unsafeLabelName("outcome") -> "adopted")
            )
            .as(true)
      case None =>
        Metrics[F]
          .incrementCounter(
            "dag_consensus_certified_recovery_total",
            Seq(unsafeLabelName("outcome") -> "state_changed")
          )
          .as(false)
    }
  }

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
        (storage.getResources(key), quorumShrinkDecisionFor(state)).tupled.flatMap {
          case (resources, shrinkDecision) =>
            val rawVotes = resources.viewChangeVotes.getOrElse((fromView, toView), Map.empty)
            val quorum = certificateQuorum(state, rawVotes, shrinkDecision)
            val votes = quorum.votes
            val q = quorum.required
            if (quorum.meets) {
              val facilitatorsHashCandidates = votes.values.map(_.value.facilitatorsHash).toSet
              facilitatorsHashCandidates.toList match {
                case singleHash :: Nil =>
                  val lastSnapshotHash = ctx.lastSnapshotHashOf(state.lastOutcome)
                  ViewChangeCertificateBuilder
                    .build(fromView, toView, singleHash, lastSnapshotHash, votes, q, quorum.voterPool) match {
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
                      ) >>
                        Metrics[F].incrementCounter(
                          "dag_consensus_vcc_assembly_total",
                          Seq(
                            unsafeLabelName("outcome") -> "build_failed",
                            unsafeLabelName("reason") -> error.code
                          )
                        )
                    case Right(vcc) =>
                      for {
                        shouldSchedule <- storage.markAssembledVccApplyScheduled(key, lastSnapshotHash, fromView, toView)
                        _ <- storage.storeAssembledVcc(key, vcc)
                        _ <- logQuorumShrinkApplied(key, "vcc_assembly", shrinkDecision, votes.keySet)
                        // Re-distribute the assembled VCC so peers that did NOT reach quorum
                        // locally for this (fromView, toView) -- e.g. due to gossip lag -- still
                        // store the VCC and can build a valid proposal when they next lead at
                        // `view > 0`. Without this, the per-peer assembly path leaves a lagging
                        // peer with an empty `assembledVccR` slot even though state.viewNumber
                        // advances via gossip, and the next leadership turn wedges with
                        // `vcc_missing_for_view_gt_0`. Targets the canonical round-start committee
                        // (excluding self) -- the cohort that could become leader at any future
                        // view of THIS round.
                        vccGossipTargets = state.roundStartFacilitators.value.toSet - ctx.selfId
                        _ <- gossip.spreadDirect(ConsensusAssembledVcc[Key](key, vcc), vccGossipTargets).whenA(shouldSchedule)
                        _ <- ConsensusLog
                          .info(
                            log,
                            Category.Phase,
                            key.show,
                            "n/a",
                            LogEvent.ViewChange,
                            "assembly" -> "quorum_reached_scheduled",
                            "fromView" -> fromView.toString,
                            "toView" -> toView.toString,
                            "votes" -> votes.size.toString,
                            "quorum" -> q.toString,
                            "applyDelayMs" -> config.viewChangeApplyDelay.toMillis.toString
                          )
                          .whenA(shouldSchedule)
                        _ <- Metrics[F]
                          .incrementCounter(
                            "dag_consensus_vcc_assembly_total",
                            Seq(
                              unsafeLabelName("outcome") -> "scheduled",
                              unsafeLabelName("reason") -> "apply_delay"
                            )
                          )
                          .whenA(shouldSchedule)
                        _ <- Async[F]
                          .start(
                            Temporal[F].sleep(config.viewChangeApplyDelay) >>
                              queue.offer(ConsensusCommand.CheckViewChangeApply(key, fromView, toView))
                          )
                          .void
                          .whenA(shouldSchedule)
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
                  ) >>
                    Metrics[F].incrementCounter(
                      "dag_consensus_vcc_assembly_total",
                      Seq(
                        unsafeLabelName("outcome") -> "divergent_facilitators_hash",
                        unsafeLabelName("reason") -> "multiple_hashes"
                      )
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

  def checkTimeoutCertificateAssembly(key: Key): F[Unit] =
    storage.getState(key).flatMap {
      case None => Async[F].unit
      case Some(state) =>
        (storage.getResources(key), quorumShrinkDecisionFor(state)).tupled.flatMap {
          case (resources, shrinkDecision) =>
            val fromView = state.viewNumber.toLong
            val toView = fromView + 1L
            val votes = resources.timeoutVotes.getOrElse((fromView, toView), Map.empty)
            val lastSnapshotHash = ctx.lastSnapshotHashOf(state.lastOutcome)

            votes.values.toList.groupBy(_.value.reason).toList.traverse_ {
              case (reason, reasonVotes) =>
                val rawVotesBySigner = reasonVotes.map(v => v.proofs.head.id.toPeerId -> v).toMap
                val quorum = certificateQuorum(state, rawVotesBySigner, shrinkDecision)
                val votesBySigner = quorum.votes
                val q = quorum.required
                votesBySigner.values.map(_.value.facilitatorsHash).toSet.toList match {
                  case singleHash :: Nil if quorum.meets =>
                    TimeoutCertificateBuilder
                      .build(fromView, toView, singleHash, lastSnapshotHash, reason, votesBySigner, q, quorum.voterPool) match {
                      case Left(error) =>
                        ConsensusLog.warn(
                          log,
                          Category.Phase,
                          key.show,
                          "n/a",
                          LogEvent.ViewChange,
                          "assembly" -> "timeout_cert_build_failed",
                          "reason" -> error.code,
                          "timeoutReason" -> reason.toString,
                          "fromView" -> fromView.toString,
                          "toView" -> toView.toString,
                          "votes" -> votesBySigner.size.toString,
                          "quorum" -> q.toString
                        ) >>
                          Metrics[F].incrementCounter(
                            "dag_consensus_timeout_certificate_total",
                            Seq(
                              unsafeLabelName("outcome") -> "build_failed",
                              unsafeLabelName("reason") -> error.code
                            )
                          )
                      case Right(tc) =>
                        for {
                          shouldSchedule <- storage.markTimeoutCertificateApplyScheduled(key, lastSnapshotHash, fromView, toView)
                          _ <- storage.storeTimeoutCertificate(key, tc)
                          _ <- logQuorumShrinkApplied(key, "tc_assembly", shrinkDecision, votesBySigner.keySet)
                          _ <- ConsensusLog
                            .info(
                              log,
                              Category.Phase,
                              key.show,
                              "n/a",
                              LogEvent.ViewChange,
                              "assembly" -> "timeout_cert_assembled",
                              "timeoutReason" -> reason.toString,
                              "fromView" -> fromView.toString,
                              "toView" -> toView.toString,
                              "votes" -> votesBySigner.size.toString,
                              "quorum" -> q.toString,
                              "applyDelayMs" -> config.viewChangeApplyDelay.toMillis.toString
                            )
                            .whenA(shouldSchedule)
                          _ <- Metrics[F]
                            .incrementCounter(
                              "dag_consensus_timeout_certificate_total",
                              Seq(
                                unsafeLabelName("outcome") -> "scheduled",
                                unsafeLabelName("reason") -> reason.toString
                              )
                            )
                            .whenA(shouldSchedule)
                          _ <- Metrics[F]
                            .incrementCounter(
                              "dag_consensus_timeout_certificate_total",
                              Seq(
                                unsafeLabelName("outcome") -> "duplicate_suppressed",
                                unsafeLabelName("reason") -> reason.toString
                              )
                            )
                            .unlessA(shouldSchedule)
                          _ <- Async[F]
                            .start(
                              Temporal[F].sleep(config.viewChangeApplyDelay) >>
                                queue.offer(ConsensusCommand.CheckTimeoutCertificateApply(key, fromView, toView))
                            )
                            .void
                            .whenA(shouldSchedule)
                        } yield ()
                    }
                  case Nil =>
                    log.debug(
                      ConsensusLog.format(
                        Category.Phase,
                        key.show,
                        "n/a",
                        LogEvent.ViewChange,
                        "assembly" -> "timeout_waiting_for_quorum",
                        "timeoutReason" -> reason.toString,
                        "votes" -> votesBySigner.size.toString,
                        "quorum" -> q.toString
                      )
                    )
                  case _ :: Nil =>
                    log.debug(
                      ConsensusLog.format(
                        Category.Phase,
                        key.show,
                        "n/a",
                        LogEvent.ViewChange,
                        "assembly" -> "timeout_waiting_for_quorum",
                        "timeoutReason" -> reason.toString,
                        "votes" -> votesBySigner.size.toString,
                        "quorum" -> q.toString,
                        "hashes" -> "1"
                      )
                    )
                  case multiple =>
                    ConsensusLog.warn(
                      log,
                      Category.Phase,
                      key.show,
                      "n/a",
                      LogEvent.ViewChange,
                      "assembly" -> "timeout_divergent_facilitators_hash",
                      "timeoutReason" -> reason.toString,
                      "hashes" -> multiple.size.toString,
                      "fromView" -> fromView.toString,
                      "toView" -> toView.toString
                    ) >>
                      Metrics[F].incrementCounter(
                        "dag_consensus_timeout_certificate_total",
                        Seq(
                          unsafeLabelName("outcome") -> "divergent_facilitators_hash",
                          unsafeLabelName("reason") -> reason.toString
                        )
                      )
                }
            }
        }
    }

  def checkTimeoutCertificateApply(key: Key, fromView: Long, toView: Long): F[Unit] =
    storage.getState(key).flatMap {
      case None => Async[F].unit
      case Some(state) if ctx.ops.isFinished(state.status) =>
        Metrics[F].incrementCounter(
          "dag_consensus_timeout_certificate_apply_total",
          Seq(
            unsafeLabelName("outcome") -> "stale",
            unsafeLabelName("reason") -> "round_finished"
          )
        )
      case Some(state) if state.viewNumber.toLong =!= fromView =>
        Metrics[F].incrementCounter(
          "dag_consensus_timeout_certificate_apply_total",
          Seq(
            unsafeLabelName("outcome") -> "stale",
            unsafeLabelName("reason") -> "view_already_changed"
          )
        )
      case Some(state) =>
        storage.getResources(key).flatMap { resources =>
          resources.timeoutCertificates.get((fromView, toView)) match {
            case None =>
              Metrics[F].incrementCounter(
                "dag_consensus_timeout_certificate_apply_total",
                Seq(
                  unsafeLabelName("outcome") -> "waiting_for_certificate",
                  unsafeLabelName("reason") -> "not_stored"
                )
              )
            case Some(tc) =>
              applyCertifiedTimeoutCertificate(key, state, resources, fromView, toView, tc.reason)
          }
        }
    }

  def checkViewChangeApply(key: Key, fromView: Long, toView: Long): F[Unit] =
    storage.getState(key).flatMap {
      case None => Async[F].unit
      case Some(state) if ctx.ops.isFinished(state.status) =>
        Metrics[F].incrementCounter(
          "dag_consensus_vcc_apply_total",
          Seq(
            unsafeLabelName("outcome") -> "stale",
            unsafeLabelName("reason") -> "round_finished"
          )
        )
      case Some(state) if state.viewNumber.toLong =!= fromView =>
        Metrics[F].incrementCounter(
          "dag_consensus_vcc_apply_total",
          Seq(
            unsafeLabelName("outcome") -> "stale",
            unsafeLabelName("reason") -> "view_already_changed"
          )
        )
      case Some(state) =>
        storage.getResources(key).flatMap { resources =>
          val currentKindHasCoreDeclarations = ctx.ops
            .maybeCollectingKind(state.status)
            .exists(kind =>
              resources.peerDeclarationsMap.exists {
                case (peerId, declarations) =>
                  state.coreFacilitators.value.contains(peerId) && ctx.ops.kindGetter(kind)(declarations).isDefined
              }
            )
          val localProgress =
            ctx.ops.isSignaturesPhase(state.status) || (ctx.ops.isProposalPhase(state.status) && currentKindHasCoreDeclarations)

          if (localProgress)
            Metrics[F].incrementCounter(
              "dag_consensus_vcc_apply_total",
              Seq(
                unsafeLabelName("outcome") -> "deferred_local_progress",
                unsafeLabelName("reason") -> "proposal_or_signature_in_progress"
              )
            ) >>
              queue.offer(ConsensusCommand.CheckUpdate(key)) >>
              Async[F]
                .start(
                  Temporal[F].sleep(config.viewChangeApplyDelay / 2) >>
                    queue.offer(ConsensusCommand.CheckViewChangeApply(key, fromView, toView))
                )
                .void
          else
            applyCertifiedViewChange(key, state, resources, fromView, toView)
        }
    }

  private def applyCertifiedViewChange(
    key: Key,
    state: ConsensusState[Key, Status, Outcome, Kind],
    resources: ConsensusResources[Artifact, Kind],
    fromView: Long,
    toView: Long
  ): F[Unit] = quorumShrinkDecisionFor(state).flatMap { shrinkDecision =>
    val rawVotes = resources.viewChangeVotes.getOrElse((fromView, toView), Map.empty)
    val quorum = certificateQuorum(state, rawVotes, shrinkDecision)
    val votes = quorum.votes
    val q = quorum.required

    if (!quorum.meets)
      Metrics[F].incrementCounter(
        "dag_consensus_vcc_apply_total",
        Seq(
          unsafeLabelName("outcome") -> "waiting_for_quorum",
          unsafeLabelName("reason") -> "insufficient_votes"
        )
      )
    else
      votes.values.map(_.value.facilitatorsHash).toSet.toList match {
        case singleHash :: Nil =>
          val lastSnapshotHash = ctx.lastSnapshotHashOf(state.lastOutcome)
          ViewChangeCertificateBuilder.build(fromView, toView, singleHash, lastSnapshotHash, votes, q, quorum.voterPool) match {
            case Left(error) =>
              ConsensusLog.warn(
                log,
                Category.Phase,
                key.show,
                "n/a",
                LogEvent.ViewChange,
                "assembly" -> "vcc_apply_build_failed",
                "reason" -> error.code,
                "fromView" -> fromView.toString,
                "toView" -> toView.toString,
                "votes" -> votes.size.toString,
                "quorum" -> q.toString
              ) >>
                Metrics[F].incrementCounter(
                  "dag_consensus_vcc_apply_total",
                  Seq(
                    unsafeLabelName("outcome") -> "build_failed",
                    unsafeLabelName("reason") -> error.code
                  )
                )
            case Right(vcc) =>
              val viewMembershipPolicy = ctx.membershipPolicy.forCertifiedView(state.certifiedConsensusActive)
              val leaderPool = viewMembershipPolicy.certifiedViewChangeLeaderPool(
                state.coreFacilitators.value,
                state.facilitators.value,
                state.roundStartFacilitators.value
              )
              val newLeader = facilitatorSelector.selectLeader(leaderPool, state.entropy, toView.toInt)
              val resetStatus = ctx.ops.freshCollectingFacilities(state.status)
              val modify: ConsensusStorage.ModifyStateFn[F, Key, Status, Outcome, Kind, Boolean] =
                new ConsensusStorage.ModifyStateFn[F, Key, Status, Outcome, Kind, Boolean] {
                  def apply(
                    maybeState: Option[ConsensusState[Key, Status, Outcome, Kind]]
                  ): F[Option[(Option[ConsensusState[Key, Status, Outcome, Kind]], Boolean)]] =
                    maybeState match {
                      case Some(s) if s.viewNumber.toLong === fromView =>
                        val canonicalFacilitators = viewMembershipPolicy.canonicalFacilitators(
                          s.facilitators.value,
                          s.roundStartFacilitators.value
                        )
                        val membershipState = s.copy(facilitators = Facilitators(canonicalFacilitators))
                        val updated: ConsensusState[Key, Status, Outcome, Kind] = resetStatus match {
                          case Some(fresh) =>
                            membershipState.copy(
                              viewNumber = toView.toInt,
                              leader = newLeader,
                              status = fresh,
                              withdrawnFacilitators = WithdrawnFacilitators.empty
                            )
                          case None =>
                            membershipState.copy(
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
                _ <- logQuorumShrinkApplied(key, "vcc_apply", shrinkDecision, votes.keySet)
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
                    "leaderPool" -> (if (leaderPool == state.coreFacilitators.value) "core" else "facilitators_fallback"),
                    "leaderPoolSize" -> leaderPool.size.toString,
                    "newLeader" -> ConsensusLog.pid(newLeader),
                    "statusReset" -> resetStatus.isDefined.toString
                  )
                  .whenA(didAdvance)
                _ <- Metrics[F]
                  .incrementCounter(
                    "dag_consensus_vcc_apply_total",
                    Seq(
                      unsafeLabelName("outcome") -> "advanced",
                      unsafeLabelName("reason") -> "none"
                    )
                  )
                  .whenA(didAdvance)
                _ <- Metrics[F]
                  .incrementCounter(
                    "dag_consensus_vcc_apply_total",
                    Seq(
                      unsafeLabelName("outcome") -> "not_advanced_race",
                      unsafeLabelName("reason") -> "state_already_advanced"
                    )
                  )
                  .unlessA(didAdvance)
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
            "assembly" -> "vcc_apply_divergent_facilitators_hash",
            "hashes" -> multiple.size.toString,
            "fromView" -> fromView.toString,
            "toView" -> toView.toString
          ) >>
            Metrics[F].incrementCounter(
              "dag_consensus_vcc_apply_total",
              Seq(
                unsafeLabelName("outcome") -> "divergent_facilitators_hash",
                unsafeLabelName("reason") -> "multiple_hashes"
              )
            )
      }
  }

  private def applyCertifiedTimeoutCertificate(
    key: Key,
    state: ConsensusState[Key, Status, Outcome, Kind],
    resources: ConsensusResources[Artifact, Kind],
    fromView: Long,
    toView: Long,
    reason: TimeoutReason
  ): F[Unit] = quorumShrinkDecisionFor(state).flatMap { shrinkDecision =>
    val votes = resources.timeoutVotes.getOrElse((fromView, toView), Map.empty)
    val rawReasonVotes = votes.collect { case (pid, signed) if signed.value.reason === reason => pid -> signed }
    val quorum = certificateQuorum(state, rawReasonVotes, shrinkDecision)
    val reasonVotes = quorum.votes
    val q = quorum.required

    if (!quorum.meets)
      Metrics[F].incrementCounter(
        "dag_consensus_timeout_certificate_apply_total",
        Seq(
          unsafeLabelName("outcome") -> "waiting_for_quorum",
          unsafeLabelName("reason") -> "insufficient_votes"
        )
      )
    else
      reasonVotes.values.map(_.value.facilitatorsHash).toSet.toList match {
        case singleHash :: Nil =>
          val lastSnapshotHash = ctx.lastSnapshotHashOf(state.lastOutcome)
          TimeoutCertificateBuilder.build(fromView, toView, singleHash, lastSnapshotHash, reason, reasonVotes, q, quorum.voterPool) match {
            case Left(error) =>
              ConsensusLog.warn(
                log,
                Category.Phase,
                key.show,
                "n/a",
                LogEvent.ViewChange,
                "assembly" -> "timeout_cert_apply_build_failed",
                "reason" -> error.code,
                "timeoutReason" -> reason.toString,
                "fromView" -> fromView.toString,
                "toView" -> toView.toString,
                "votes" -> reasonVotes.size.toString,
                "quorum" -> q.toString
              ) >>
                Metrics[F].incrementCounter(
                  "dag_consensus_timeout_certificate_apply_total",
                  Seq(
                    unsafeLabelName("outcome") -> "build_failed",
                    unsafeLabelName("reason") -> error.code
                  )
                )
            case Right(_) =>
              val shrinkFloor = q
              val currentActive = state.facilitators.value
              // Layer policy remains the authority for N+1 health-derived membership changes. V35 additionally freezes the current
              // round in both layers, so a Currency eviction certificate may affect N+1 without shrinking N during this view change.
              val viewMembershipPolicy = ctx.membershipPolicy.forCertifiedView(state.certifiedConsensusActive)
              val timeoutMembership = viewMembershipPolicy.timeoutMembership(
                facilitators = currentActive,
                coreFacilitators = state.coreFacilitators.value,
                roundStartFacilitators = state.roundStartFacilitators.value,
                timeoutVoters = reasonVotes.keySet,
                shrinkFloor = shrinkFloor
              )
              val effectiveActive = timeoutMembership.evaluatedActive
              val shrunk = timeoutMembership.shrinkApplied
              val leaderPool = timeoutMembership.leaderPool
              val newLeader = facilitatorSelector.selectLeader(leaderPool, state.entropy, toView.toInt)
              val resetStatus = ctx.ops.freshCollectingFacilities(state.status)
              val modify: ConsensusStorage.ModifyStateFn[F, Key, Status, Outcome, Kind, Boolean] =
                new ConsensusStorage.ModifyStateFn[F, Key, Status, Outcome, Kind, Boolean] {
                  def apply(
                    maybeState: Option[ConsensusState[Key, Status, Outcome, Kind]]
                  ): F[Option[(Option[ConsensusState[Key, Status, Outcome, Kind]], Boolean)]] =
                    maybeState match {
                      case Some(s) if s.viewNumber.toLong === fromView =>
                        // Use the live CAS state for legacy no-shrink. GL0 retain mode
                        // canonicalizes that live active view back to the frozen round-start
                        // committee; a certified Currency shrink keeps its certified result.
                        val activeAfterCertifiedShrink =
                          if (timeoutMembership.shrinkApplied) timeoutMembership.facilitators else s.facilitators.value
                        val canonicalFacilitators = viewMembershipPolicy.canonicalFacilitators(
                          activeAfterCertifiedShrink,
                          s.roundStartFacilitators.value
                        )
                        val coreAfterCertifiedShrink =
                          if (timeoutMembership.shrinkApplied) timeoutMembership.coreFacilitators else s.coreFacilitators.value
                        val membershipState = s.copy(
                          facilitators = Facilitators(canonicalFacilitators),
                          coreFacilitators = CoreFacilitators(coreAfterCertifiedShrink)
                        )
                        val updated: ConsensusState[Key, Status, Outcome, Kind] = resetStatus match {
                          case Some(fresh) =>
                            membershipState.copy(
                              viewNumber = toView.toInt,
                              leader = newLeader,
                              status = fresh,
                              withdrawnFacilitators = WithdrawnFacilitators.empty
                            )
                          case None =>
                            membershipState.copy(
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
                advanced <- storage.condModifyState[Boolean](key)(modify)
                didAdvance = advanced.getOrElse(false)
                _ <- logQuorumShrinkApplied(key, "tc_apply", shrinkDecision, reasonVotes.keySet).whenA(didAdvance)
                _ <- ConsensusLog
                  .info(
                    log,
                    Category.Phase,
                    key.show,
                    "n/a",
                    LogEvent.ViewChange,
                    "assembly" -> "timeout_cert_advanced",
                    "timeoutReason" -> reason.toString,
                    "fromView" -> fromView.toString,
                    "toView" -> toView.toString,
                    "votes" -> reasonVotes.size.toString,
                    "quorum" -> q.toString,
                    "leaderPool" -> (if (shrunk) "certified_shrink"
                                     else if (leaderPool == state.coreFacilitators.value) "core"
                                     else "facilitators_fallback"),
                    "leaderPoolSize" -> leaderPool.size.toString,
                    "newLeader" -> ConsensusLog.pid(newLeader),
                    "statusReset" -> resetStatus.isDefined.toString,
                    "certifiedShrink" -> shrunk.toString,
                    "shrinkFrom" -> currentActive.size.toString,
                    "shrinkTo" -> effectiveActive.size.toString,
                    "timeoutVoters" -> reasonVotes.size.toString,
                    "shrinkExclusions" -> timeoutMembership.exclusionCount.toString,
                    "membershipPolicy" -> viewMembershipPolicy.productPrefix
                  )
                  .whenA(didAdvance)
                _ <- ConsensusLog
                  .info(
                    log,
                    Category.Phase,
                    key.show,
                    "n/a",
                    LogEvent.ViewChange,
                    "assembly" -> "timeout_certified_shrink",
                    "timeoutReason" -> reason.toString,
                    "fromView" -> fromView.toString,
                    "toView" -> toView.toString,
                    "fromSize" -> currentActive.size.toString,
                    "toSize" -> effectiveActive.size.toString,
                    "floor" -> shrinkFloor.toString,
                    "timeoutVoters" -> reasonVotes.size.toString,
                    "recentSignerPool" -> timeoutMembership.recentSignerPoolSize.toString,
                    "excluded" -> timeoutMembership.exclusionCount.toString
                  )
                  .whenA(didAdvance && shrunk)
                _ <- Metrics[F]
                  .incrementCounter(
                    "dag_consensus_timeout_certificate_apply_total",
                    Seq(
                      unsafeLabelName("outcome") -> "advanced",
                      unsafeLabelName("reason") -> reason.toString
                    )
                  )
                  .whenA(didAdvance)
                _ <- Metrics[F]
                  .incrementCounter(
                    "dag_consensus_timeout_certificate_apply_total",
                    Seq(
                      unsafeLabelName("outcome") -> "not_advanced_race",
                      unsafeLabelName("reason") -> "state_already_advanced"
                    )
                  )
                  .unlessA(didAdvance)
                _ <- Metrics[F]
                  .incrementCounter(
                    "dag_consensus_certified_shrink_total",
                    Seq(
                      unsafeLabelName("outcome") -> (if (shrunk) "applied"
                                                     else if (timeoutMembership.shrinkEvaluated) "not_needed"
                                                     else "disabled_by_policy"),
                      unsafeLabelName("reason") -> reason.toString
                    )
                  )
                  .whenA(didAdvance)
                _ <- Metrics[F].updateGauge("dag_consensus_certified_shrink_retained_size", effectiveActive.size.toLong).whenA(didAdvance)
                _ <- Metrics[F]
                  .updateGauge("dag_consensus_certified_shrink_missing_size", timeoutMembership.exclusionCount.toLong)
                  .whenA(didAdvance)
                _ <- Metrics[F].updateGauge("dag_consensus_view_number", toView).whenA(didAdvance)
                _ <- queue.offer(ConsensusCommand.CheckUpdate(key)).whenA(didAdvance)
              } yield ()
          }
        case Nil =>
          Metrics[F].incrementCounter(
            "dag_consensus_timeout_certificate_apply_total",
            Seq(
              unsafeLabelName("outcome") -> "waiting_for_quorum",
              unsafeLabelName("reason") -> "reason_votes_missing"
            )
          )
        case multiple =>
          ConsensusLog.warn(
            log,
            Category.Phase,
            key.show,
            "n/a",
            LogEvent.ViewChange,
            "assembly" -> "timeout_cert_apply_divergent_facilitators_hash",
            "hashes" -> multiple.size.toString,
            "timeoutReason" -> reason.toString,
            "fromView" -> fromView.toString,
            "toView" -> toView.toString
          ) >>
            Metrics[F].incrementCounter(
              "dag_consensus_timeout_certificate_apply_total",
              Seq(
                unsafeLabelName("outcome") -> "divergent_facilitators_hash",
                unsafeLabelName("reason") -> reason.toString
              )
            )
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
      case Some(state)
          if ctx.membershipPolicy.acceptsEvictionCertificates ||
            ctx.membershipPolicy.allowsCertifiedAtomicReplacement(state.certifiedConsensusActive) =>
        checkEvictionAssemblyEnabled(key, target)
      case _ =>
        ConsensusLog.debug(
          log,
          Category.Phase,
          key.show,
          "n/a",
          LogEvent.Eviction,
          "assembly" -> "disabled_by_membership_policy",
          "target" -> ConsensusLog.pid(target)
        )
    }

  /** Exact rc.6 assembly path, reached only for layers whose membership policy enables ECS. */
  private def checkEvictionAssemblyEnabled(key: Key, target: PeerId): F[Unit] =
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
          // v19: ECS assembly quorum threshold computed against the Core committee --
          // mirrors `validateProposalEcs` in the advancer. The signer pool stays open to
          // all of `roundStartFacilitators` (witness widening still adds historical
          // participants from peerQuality), but the LIVENESS denominator is Core-sized so
          // a leader assembling with q Core-derived signatures will validate against
          // every follower's matching denominator. Integer math via `QuorumPolicy.fromFraction`.
          val n = state.coreFacilitators.value.size
          val atomicReplacement =
            ctx.membershipPolicy.allowsCertifiedAtomicReplacement(state.certifiedConsensusActive)
          val q =
            if (atomicReplacement) CertifiedConsensus.requiredCoreQuorum(n, config.quorumThresholdFraction)
            else math.max(1, QuorumPolicy.fromFraction(n, config.quorumThresholdFraction))
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
                    // Tier-1 finality-participation eviction is Core-attested. Core-target
                    // stall eviction keeps the wider historical witness recovery lane that
                    // prevents a damaged Core committee from making its own repair impossible.
                    // The shared selector is also used by both proposal validators so assembly
                    // and acceptance cannot drift.
                    val witnessPool =
                      if (atomicReplacement) state.coreFacilitators.value.toSet - target
                      else
                        EvictionVoterPool.select(
                          target,
                          state.tier1Facilitators.value.contains(target),
                          state.coreFacilitators.value.toSet,
                          widerWitnessPool(state, target)
                        )
                    val expectedLastSnap = ctx.lastSnapshotHashOf(state.lastOutcome)
                    EvictionCertificateBuilder
                      .build(target, singleReason, facHash, expectedLastSnap, matchingVotes, q, witnessPool) match {
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
    * `AdmissionCertificate` for `target` once the `AdmissionVote` store holds at least quorum unique voters agreeing on `facilitatorsHash`.
    *
    * Like B1, certificate assembly is side-effect free for `state.facilitators` / `state.admittedFacilitators` — those mutations happen at
    * advancer proposal-acceptance time (Phase 6 of the B2 rollout).
    */
  def checkAdmissionAssembly(key: Key, target: PeerId): F[Unit] =
    storage.getState(key).flatMap {
      case None => Async[F].unit
      case Some(state) =>
        storage.getResources(key).flatMap { resources =>
          val votes = resources.admissionVotes.getOrElse(target, Map.empty)
          // v19: ACS assembly quorum threshold computed against the Core committee --
          // mirrors `validateProposalAcs` in the advancer. See ECS assembly above for the
          // full rationale on decoupling the Core-sized liveness quorum from the broad
          // signing committee. Integer math via `QuorumPolicy.fromFraction`.
          val n = state.coreFacilitators.value.size
          val q = math.max(1, QuorumPolicy.fromFraction(n, config.quorumThresholdFraction))
          // Open expansion is certified by Core only: Tier 1 remains outside the liveness
          // machinery and cannot become necessary for committee growth. Penalty readmission is
          // deliberately different. A peer already in probation may need the historical witness
          // lane to recover from the exact committee failure that evicted it, so retain the wider
          // deterministic pool for that path.
          val isProbationReadmission = ctx.probationPeersOf(state.lastOutcome).contains(target)
          val voterPool = AdmissionVoterPool.select(
            target,
            isProbationReadmission,
            state.coreFacilitators.value.toSet,
            widerWitnessPool(state, target)
          )
          val eligibleVotes = votes.filter { case (voter, _) => voterPool.contains(voter) }

          if (eligibleVotes.size >= q) {
            val byHash: Map[Hash, Int] =
              eligibleVotes.values.groupBy(_.value.facilitatorsHash).view.mapValues(_.size).toMap
            byHash.toList.sortBy(-_._2) match {
              case (facHash, voteCount) :: _ if voteCount >= q =>
                val matchingVotes = eligibleVotes.filter { case (_, signed) => signed.value.facilitatorsHash == facHash }
                val reasons = matchingVotes.values.map(_.value.reason).toSet
                reasons.toList match {
                  case singleReason :: Nil =>
                    val expectedLastSnap = ctx.lastSnapshotHashOf(state.lastOutcome)
                    AdmissionCertificateBuilder
                      .build(target, singleReason, facHash, expectedLastSnap, matchingVotes, q, voterPool) match {
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
                          queue.offer(ConsensusCommand.CheckUpdate(key)) >>
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
                            "reason" -> singleReason.toString,
                            "bootstrap" -> ctx.isInBootstrap(state.lastOutcome).toString
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
                "votes" -> eligibleVotes.size.toString,
                "discardedNonVoters" -> (votes.size - eligibleVotes.size).toString,
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

          runOutcomeHook("finalized", outcome)(ctx.onOutcomeFinalized) >>
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
              val signerSet = signedArtifact.proofs.toList.map(_.id.toPeerId).toSet
              val activeSet = newState.facilitators.value.toSet
              val signerIds = signerSet.toList.map(ConsensusLog.pid).sorted.mkString(",")
              val facilitatorIds = activeSet.toList.map(ConsensusLog.pid).sorted.mkString(",")
              val missingActiveSigners = (activeSet -- signerSet).toList.sorted
              val missingActiveSignerIds = missingActiveSigners.map(ConsensusLog.pid).mkString(",")
              val signerCount = signedArtifact.proofs.size
              val missingActiveSignerCount = missingActiveSigners.size
              val missingActiveSignerRatio =
                if (activeSet.nonEmpty) missingActiveSignerCount.toDouble / activeSet.size.toDouble else 0.0

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
                  "signerCount" -> signerCount.toString,
                  "signerIds" -> signerIds,
                  "missingActiveSignerCount" -> missingActiveSignerCount.toString,
                  "missingActiveSignerIds" -> missingActiveSignerIds,
                  "leader" -> ConsensusLog.pid(newState.leader),
                  "leaderScore" -> f"$leaderScore%.2f",
                  "view" -> newState.viewNumber.toString
                ) ++
                  (if (withdrawnCount > 0) Seq("withdrawn" -> withdrawnCount.toString) else Seq.empty) ++
                  (if (removedCount > 0) Seq("removed" -> removedCount.toString) else Seq.empty)): _*
              ) >>
                Metrics[F].updateGauge("dag_consensus_last_signer_count", signerCount.toLong) >>
                Metrics[F].updateGauge("dag_consensus_missing_active_signer_count", missingActiveSignerCount.toLong) >>
                Metrics[F].updateGauge("dag_consensus_missing_active_signer_ratio", missingActiveSignerRatio) >>
                Metrics[F].incrementCounter(
                  "dag_consensus_outcome_signer_count_total",
                  Seq(unsafeLabelName("signer_count") -> signerCount.toString)
                ) >>
                Metrics[F].incrementCounter(
                  "dag_consensus_outcome_signer_vs_active_total",
                  Seq(
                    unsafeLabelName("signer_count") -> signerCount.toString,
                    unsafeLabelName("active_size") -> newState.facilitators.value.size.toString
                  )
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
    case _: StateTransitions.SelfStillInProbation => "self_in_probation"
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
          StateTransitions.downloadOutcomeDisposition(keyMatch, artifactMatch, contextMatch, isRecovery) match {
            case StateTransitions.DownloadOutcomeDisposition.AcceptExact(isRecoveryEffective) =>
              (o, isRecoveryEffective).pure[F]

            // If the specific-outcome endpoint reports Conflict, fetchOutcomeFromCluster falls
            // back to the peer's latest outcome. That can legitimately be N+1 after download
            // converged at N. Accepting it into consensus without also moving layer storage to
            // N+1 creates a torn handoff: consensus next emits N+2 while application storage
            // still requires N+1. Align the layer before installing the newer outcome.
            case StateTransitions.DownloadOutcomeDisposition.AcceptAndAlignApplicationStorage =>
              ctx.advancer
                .synchronizeDownloadedOutcome(outcomeArtifact.get(o), outcomeContext.get(o)) >>
                ConsensusLog.info(
                  log,
                  Category.Lifecycle,
                  outcomeKey.get(o).show,
                  "n/a",
                  LogEvent.DownloadInitStart,
                  "stage" -> "newer_outcome_storage_aligned",
                  "requestedKey" -> key.show,
                  "acceptedKey" -> outcomeKey.get(o).show
                ) >>
                Metrics[F].incrementCounter("dag_consensus_download_newer_outcome_storage_aligned_total") >>
                (o, true).pure[F]

            case StateTransitions.DownloadOutcomeDisposition.Reject =>
              new Throwable(
                s"[DownloadInit] Outcome validation failed after retries for key=$key: " +
                  s"keyMatch=$keyMatch, artifactMatch=$artifactMatch, contextMatch=$contextMatch"
              ).raiseError[F, (Outcome, Boolean)]
          }
        }
      // B2 readmission gate: refuse to facilitate while self is on probation per the carried
      // outcome. A peer that was B1-evicted during isolation comes back
      // via recovery with a downloaded snapshot containing a `readmissionCountdown[selfId]` entry,
      // including a sticky entry whose countdown reached zero. The
      // cluster's state creator excludes probation peers from `state.facilitators`; if we
      // ignore that and emit Facility/Proposal/Signature anyway, our declarations land in nobody's
      // expected committee and the round wedges at `progress=1/5` until the whole 90s phase
      // timeout fires (gl0-4 fork-recovery E2E).
      //
      // Instead, raise `SelfStillInProbation`. A layer with the direct probation-probe capability
      // keeps the node stably Observing and re-issues initFromDownload after backoff; legacy layers
      // retain the rc.6 recovery-download behavior. Once the cluster has emitted a quorum-witnessed
      // AdmissionCertificate clearing self from probation, the check passes and recovery proceeds.
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
            ) >> new StateTransitions.SelfStillInProbation(ctx.selfId, key.toString)
            .raiseError[F, Unit]
        } else Async[F].unit
      // Mark recovery completion at this key. The local self-yield this used to drive
      // (StallDetector EARLY_VIEW_CHANGE on `self_recently_recovered_leader_cooldown`) was
      // removed in alpha.96 because it broke committee symmetry and caused a leader
      // split-brain. The marker is still written so a future deterministic-across-committee
      // reintroduction can reuse it without new plumbing. See
      // `ConsensusEngineContext.recoveredAtKeyRef` for full rationale.
      _ <- ctx.recoveredAtKeyRef.set(Some(outcomeKey.get(outcome)))
      _ <- storage
        .trySetInitialConsensusOutcome(outcome)
        .ifM(
          ifFalse = new Throwable(s"[DownloadInit] Failed to initialize consensus storage").raiseError[F, Unit],
          ifTrue = runOutcomeHook("download_initialized", outcome)(ctx.onOutcomeInitialized) >>
            downloadReadyPromotionAllowed(outcome).flatMap { promoteToReady =>
              val targetState = if (promoteToReady) NodeState.Ready else NodeState.WaitingForReady
              // Initial download promotion is also the candidate-admission path. A peer exposes
              // its next-round registration key through `observationKey`; clearing it here makes
              // the peer visible as Ready but invisible to committee selection, so bootstrap can
              // collapse back to a singleton facilitator.
              storage.clearObservationKey.whenA(promoteToReady && isRecoveryEffective) >>
                ctx.nodeStorage.tryModifyState(NodeState.Observing, targetState) >>
                Metrics[F].incrementCounter(
                  "dag_consensus_init_download_target_state_total",
                  Seq(unsafeLabelName("target_state") -> targetState.entryName)
                ) >>
                Metrics[F].incrementCounter(
                  "dag_consensus_init_download_ready_promotion_total",
                  Seq(unsafeLabelName("result") -> (if (promoteToReady) "promoted" else "waiting_for_ready"))
                ) >>
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
            }
        )
    } yield ())
      .flatTap(_ => initDownloadOutcome("success"))
      .onError { case err => initDownloadOutcome(classifyInitDownloadError(err)) }

  def initFromRollback(key: Key, outcome: Outcome, deferFirstRound: Boolean = false): F[Unit] =
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
      initialized <- storage.trySetInitialConsensusOutcome(outcome)
      _ <-
        (runOutcomeHook("rollback_initialized", outcome)(ctx.onOutcomeInitialized) >>
          runOutcomeHook("rollback_safety_pruned", outcome)(ctx.onOutcomeRollbackInitialized)).whenA(initialized)
      _ <- ConsensusLog.info(
        log,
        Category.Lifecycle,
        key.toString,
        "n/a",
        LogEvent.RollbackBootstrapActive,
        "mode" -> "checkpoint_server",
        "action" -> "serving_initial_outcome_before_first_round"
      )
      _ <- Metrics[F].incrementCounter("dag_consensus_rollback_bootstrap_active_total")
      // Set joining grace period to use relaxed timeouts for first rounds after rollback.
      // Without this, the rollback node uses aggressive timeouts while peers are still
      // downloading to the rollback ordinal, leading to premature stall detection.
      _ <- ctx.nodeStorage.setJoiningGracePeriod
      // GL0 full-network rollback orchestration starts one rollback node first, then
      // validators join and confirm the downloaded outcome. Deferring the first round
      // lets those validators reach Ready before readiness gates evaluate the cluster.
      _ <-
        if (deferFirstRound) scheduleRollbackFirstRound(key)
        else queue.offer(StartRound(TimeTrigger.some))
    } yield ()

  private def scheduleRollbackFirstRound(key: Key): F[Unit] = {
    val pollInterval = ctx.config.timeTriggerInterval
    val maxDelay = pollInterval * 2L

    def status: F[StateTransitions.RollbackFirstRoundQuorumStatus] =
      (ctx.nodeStorage.getNodeState, ctx.clusterStorage.getResponsivePeers).mapN { (nodeState, peers) =>
        StateTransitions.rollbackFirstRoundQuorumStatus(
          selfReady = nodeState === NodeState.Ready,
          externalReadyPeers = peers.count(_.state === NodeState.Ready),
          activeFacilitatorFloor = ctx.config.activeFacilitatorFloor,
          quorumThresholdFraction = ctx.config.quorumThresholdFraction
        )
      }

    def logAndStart(s: StateTransitions.RollbackFirstRoundQuorumStatus, elapsed: FiniteDuration, reason: String): F[Unit] =
      ConsensusLog.info(
        log,
        Category.Lifecycle,
        key.toString,
        "n/a",
        LogEvent.RollbackQuorumFeasible,
        "nodeReady" -> s.selfReady.toString,
        "externalReadyPeers" -> s.externalReadyPeers.toString,
        "participantsIncludingSelf" -> s.participantsIncludingSelf.toString,
        "required" -> s.required.toString,
        "activeFacilitatorFloor" -> s.activeFacilitatorFloor.toString,
        "quorumFeasible" -> s.quorumFeasible.toString,
        "elapsed" -> elapsed.toString,
        "reason" -> reason
      ) >>
        Metrics[F].incrementCounter(
          "dag_consensus_rollback_quorum_feasible_before_first_round_total",
          Seq(unsafeLabelName("feasible") -> s.quorumFeasible.toString, unsafeLabelName("reason") -> reason)
        ) >>
        queue.offer(StartRound(TimeTrigger.some))

    def waitLoop(elapsed: FiniteDuration): F[Unit] =
      Temporal[F].sleep(pollInterval) >>
        status.flatMap { s =>
          val nextElapsed = elapsed + pollInterval
          if (s.selfReady && s.quorumFeasible) logAndStart(s, nextElapsed, "ready_quorum_feasible")
          else if (s.selfReady && nextElapsed >= maxDelay) logAndStart(s, nextElapsed, "max_delay_elapsed")
          else waitLoop(nextElapsed)
        }

    ConsensusLog.info(
      log,
      Category.Lifecycle,
      key.toString,
      "n/a",
      LogEvent.RollbackFirstRoundDeferred,
      "pollInterval" -> pollInterval.toString,
      "maxDelay" -> maxDelay.toString
    ) >>
      Metrics[F].incrementCounter("dag_consensus_rollback_first_round_deferred_total") >>
      Async[F]
        .start(
          waitLoop(0.seconds).handleErrorWith(err => log.error(err)("Rollback first-round scheduler failed"))
        )
        .void
  }

  private def fetchOutcomeFromCluster(
    key: Key,
    artifact: Signed[Artifact],
    context: Ctx,
    isRecovery: Boolean
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

  private def downloadReadyPromotionAllowed(outcome: Outcome): F[Boolean] = {
    val outcomeConsensusKey = outcomeKey.get(outcome)
    val expectedArtifact = outcomeArtifact.get(outcome)
    val expectedContext = outcomeContext.get(outcome)
    val outcomeHash = ctx.lastSnapshotHashOf(outcome)

    def alignmentWithDownloaded(peer: Peer): F[StateTransitions.ReadyPromotionPeerAlignment] =
      ctx.consensusClient
        .getSpecificConsensusOutcome(GetConsensusOutcomeRequest(outcomeConsensusKey))
        .run(peer)
        .map[StateTransitions.ReadyPromotionPeerAlignment] {
          case Some(peerOutcome) =>
            val aligned = outcomeKey.get(peerOutcome) === outcomeConsensusKey &&
              outcomeArtifact.get(peerOutcome) === expectedArtifact &&
              outcomeContext.get(peerOutcome) === expectedContext &&
              ctx.lastSnapshotHashOf(peerOutcome) === outcomeHash
            if (aligned) StateTransitions.ReadyPromotionPeerAlignment.Aligned
            else StateTransitions.ReadyPromotionPeerAlignment.Mismatched
          case None => StateTransitions.ReadyPromotionPeerAlignment.Missing
        }
        .recover {
          case UnexpectedStatus(HttpStatus.Conflict, _, _) =>
            StateTransitions.ReadyPromotionPeerAlignment.Ahead
        }
        .handleErrorWith { err =>
          ConsensusLog
            .warn(
              log,
              Category.Lifecycle,
              outcomeConsensusKey.show,
              "n/a",
              LogEvent.DownloadInitReadyPromotion,
              "peer" -> ConsensusLog.pid(peer.id),
              "result" -> "peer_check_failed",
              "error" -> Option(err.getMessage).getOrElse(err.getClass.getSimpleName)
            )
            .as(StateTransitions.ReadyPromotionPeerAlignment.Failed)
        }

    ctx.clusterStorage.getResponsivePeers.flatMap { peers =>
      val readyCandidates = peers.filter(_.state == NodeState.Ready).toList
      val required = StateTransitions.readyPromotionQuorum(readyCandidates.size + 1, config.quorumThresholdFraction)
      val requiredExternalReady = StateTransitions.readyPromotionExternalReadyFloor

      readyCandidates.traverse(alignmentWithDownloaded).flatMap { results =>
        val externalAligned = results.count(_ == StateTransitions.ReadyPromotionPeerAlignment.Aligned)
        val missing = results.count(_ == StateTransitions.ReadyPromotionPeerAlignment.Missing)
        val ahead = results.count(_ == StateTransitions.ReadyPromotionPeerAlignment.Ahead)
        val mismatched = results.count(_ == StateTransitions.ReadyPromotionPeerAlignment.Mismatched)
        val failed = results.count(_ == StateTransitions.ReadyPromotionPeerAlignment.Failed)
        val alignedWithSelf = externalAligned + 1
        val promote = mismatched === 0 && StateTransitions.readyPromotionAllowed(
          readyCandidates.size,
          externalAligned,
          required
        )
        val reason =
          if (promote) "aligned_quorum"
          else if (readyCandidates.isEmpty) "no_ready_candidates"
          else if (externalAligned === 0) "no_aligned_ready_candidates"
          else if (alignedWithSelf < required) "below_quorum"
          else if (mismatched > 0) "mismatched_ready_outcomes"
          else if (missing + ahead + failed > 0) "unavailable_ready_outcomes"
          else "not_allowed"
        ConsensusLog.info(
          log,
          Category.Lifecycle,
          outcomeConsensusKey.show,
          "n/a",
          LogEvent.DownloadInitReadyPromotion,
          "result" -> (if (promote) "promoted" else "waiting_for_ready"),
          "reason" -> reason,
          "outcomeHash" -> outcomeHash.value.take(12),
          "externalAligned" -> externalAligned.toString,
          "alignedWithSelf" -> alignedWithSelf.toString,
          "readyCandidates" -> readyCandidates.size.toString,
          "missing" -> missing.toString,
          "ahead" -> ahead.toString,
          "mismatched" -> mismatched.toString,
          "failed" -> failed.toString,
          "requiredExternalReady" -> requiredExternalReady.toString,
          "required" -> required.toString
        ) >> {
          val resultLabel = unsafeLabelName("result")
          val reasonLabel = unsafeLabelName("reason")
          Metrics[F].incrementCounter(
            "dag_consensus_init_download_ready_promotion_decision_total",
            Seq(
              resultLabel -> (if (promote) "promoted" else "waiting_for_ready"),
              reasonLabel -> reason
            )
          ) >>
            Metrics[F].updateGauge("dag_consensus_init_download_ready_candidates", readyCandidates.size.toLong) >>
            Metrics[F].updateGauge("dag_consensus_init_download_external_aligned", externalAligned.toLong) >>
            Metrics[F].updateGauge("dag_consensus_init_download_external_missing", missing.toLong) >>
            Metrics[F].updateGauge("dag_consensus_init_download_external_ahead", ahead.toLong) >>
            Metrics[F].updateGauge("dag_consensus_init_download_external_mismatched", mismatched.toLong) >>
            Metrics[F].updateGauge("dag_consensus_init_download_external_failed", failed.toLong) >>
            Metrics[F].updateGauge("dag_consensus_init_download_promotion_required", required.toLong)
        } >>
          promote.pure[F]
      }
    }
  }

  class NoValidPeersException(message: String) extends RuntimeException(message)

}

object StateTransitions {

  /** Raised when `initFromDownload` resolves an outcome that still lists self in B2 probation. Layers with a direct probation probe treat
    * this as an expected lifecycle deferral and keep Observing stable; legacy layers retain the recovery-download path.
    */
  final class SelfStillInProbation(val selfId: PeerId, val keyShow: String)
      extends RuntimeException(
        s"[DownloadInit] self ${selfId.value.value.take(8)} still in B2 readmission probation at key=$keyShow; " +
          s"deferring facilitation until cluster clears probation."
      )
      with scala.util.control.NoStackTrace

  private[consensus] sealed trait DownloadOutcomeDisposition

  private[consensus] object DownloadOutcomeDisposition {
    final case class AcceptExact(isRecoveryEffective: Boolean) extends DownloadOutcomeDisposition
    case object AcceptAndAlignApplicationStorage extends DownloadOutcomeDisposition
    case object Reject extends DownloadOutcomeDisposition
  }

  /** Classify the handoff independently of layer storage effects so the critical alignment rule is regression-tested.
    *
    * A different key is the latest-outcome fallback used when the requested outcome has already been evicted. It is accepted only with an
    * application-storage alignment. A same-key artifact or context mismatch remains invalid.
    */
  private[consensus] def downloadOutcomeDisposition(
    keyMatches: Boolean,
    artifactMatches: Boolean,
    contextMatches: Boolean,
    isRecovery: Boolean
  ): DownloadOutcomeDisposition =
    if (keyMatches && artifactMatches && contextMatches) DownloadOutcomeDisposition.AcceptExact(isRecovery)
    else if (!keyMatches) DownloadOutcomeDisposition.AcceptAndAlignApplicationStorage
    else DownloadOutcomeDisposition.Reject

  private[state] val CertifiedRecoverySampleSize: Int = 8
  private[state] val CertifiedRecoveryParallelism: Int = 4
  private[state] val CertifiedRecoveryPerPeerTimeout: FiniteDuration = 2.seconds

  /** Pick one already-verified recovery candidate only when every valid certificate names the same semantic value hash. Candidate order is
    * supplied by the caller (production sorts by source PeerId); multiple proof subsets for one value are harmless, while two values fail
    * closed.
    */
  private[consensus] def selectCertifiedRecoveryCandidate[A](candidates: List[(Hash, A)]): Either[Int, Option[A]] = {
    val byValueHash = candidates.groupBy(_._1)
    if (byValueHash.size > 1) byValueHash.size.asLeft[Option[A]]
    else candidates.headOption.map(_._2).asRight[Int]
  }

  /** Shared result of selecting the voter universe for a VCC or TC. Keeping this outside the generic state-machine instance avoids an
    * outer-instance type while preserving one implementation for both certificate families and both L0 layers.
    */
  private[consensus] final case class CertificateQuorum[A](
    votes: Map[PeerId, Signed[A]],
    voterPool: Set[PeerId],
    required: Int,
    meets: Boolean
  )

  private[consensus] sealed trait ReadyPromotionPeerAlignment

  private[consensus] object ReadyPromotionPeerAlignment {
    case object Aligned extends ReadyPromotionPeerAlignment
    case object Missing extends ReadyPromotionPeerAlignment
    case object Ahead extends ReadyPromotionPeerAlignment
    case object Mismatched extends ReadyPromotionPeerAlignment
    case object Failed extends ReadyPromotionPeerAlignment
  }

  private[consensus] final case class RollbackFirstRoundQuorumStatus(
    selfReady: Boolean,
    externalReadyPeers: Int,
    participantsIncludingSelf: Int,
    required: Int,
    activeFacilitatorFloor: Int,
    quorumFeasible: Boolean
  )

  /** View-change certificates use a Core-sized quorum, so the certified next leader must come from Core as well. The fallback preserves
    * startup/fork-recovery behavior if a malformed or transitional state has not populated Core yet.
    */
  private[consensus] def viewChangeLeaderPool(coreFacilitators: List[PeerId], facilitators: List[PeerId]): List[PeerId] =
    if (coreFacilitators.nonEmpty) coreFacilitators else facilitators

  private[consensus] def readyPromotionQuorum(peerCountIncludingSelf: Int, quorumThresholdFraction: Double): Int =
    math.max(2, math.max(1, QuorumPolicy.fromFraction(peerCountIncludingSelf, quorumThresholdFraction)))

  private[consensus] def readyPromotionExternalReadyFloor: Int = 1

  private[consensus] def readyPromotionAllowed(readyCandidates: Int, externalAligned: Int, required: Int): Boolean =
    if (readyCandidates === 1) externalAligned === 1
    else readyCandidates > 1 && externalAligned + 1 >= required

  private[consensus] def rollbackFirstRoundQuorumStatus(
    selfReady: Boolean,
    externalReadyPeers: Int,
    activeFacilitatorFloor: Int,
    quorumThresholdFraction: Double
  ): RollbackFirstRoundQuorumStatus = {
    val participantsIncludingSelf = externalReadyPeers + (if (selfReady) 1 else 0)
    val required = math.max(1, QuorumPolicy.fromFraction(participantsIncludingSelf, quorumThresholdFraction))
    val quorumFeasible =
      selfReady &&
        participantsIncludingSelf >= activeFacilitatorFloor &&
        participantsIncludingSelf >= required

    RollbackFirstRoundQuorumStatus(
      selfReady,
      externalReadyPeers,
      participantsIncludingSelf,
      required,
      activeFacilitatorFloor,
      quorumFeasible
    )
  }
}
