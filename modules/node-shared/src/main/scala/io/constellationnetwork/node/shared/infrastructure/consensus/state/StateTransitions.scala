package io.constellationnetwork.node.shared.infrastructure.consensus.state

import cats.effect.kernel.{Async, Ref, Temporal}
import cats.effect.std.Random
import cats.effect.syntax.all._
import cats.kernel.Next
import cats.syntax.all._
import cats.{Eq, Monad, Show}

import scala.collection.immutable.{SortedMap, SortedSet}
import scala.concurrent.duration._
import scala.reflect.runtime.universe.TypeTag

import io.constellationnetwork.ext.cats.syntax.next.catsSyntaxNext
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
class StateTransitions[
  F[_]: Async: Random: Metrics,
  Event,
  Key: Eq: Show: Next: TypeTag: Encoder,
  Artifact: Eq,
  Ctx: Eq,
  Status,
  Outcome: Eq,
  Kind
](
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
      // A retained post-commit effect may have failed after the state reached Finished.
      // On retry the updater legitimately reports no new transition, so inspect the current
      // state as well as a newly-updated state and resume the same finalization tail.
      currentState <- maybeUpdate.fold(storage.getState(key)) { case (_, state) => state.some.pure[F] }
      _ <- currentState.traverse_ { state =>
        advancer
          .getConsensusOutcome(state)
          .map { case (prevKey, outcome) => finalizeAndNotify(state, prevKey, outcome) }
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
                        _ <- storage.storeAssembledVcc(key, vcc)
                        shouldSchedule <- storage.markAssembledVccApplyScheduled(key, lastSnapshotHash, fromView, toView)
                        _ <- Async[F]
                          .start(
                            Temporal[F].sleep(config.viewChangeApplyDelay) >>
                              queue.offer(ConsensusCommand.CheckViewChangeApply(key, fromView, toView))
                          )
                          .void
                          .whenA(shouldSchedule)
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
                          _ <- storage.storeTimeoutCertificate(key, tc)
                          shouldSchedule <- storage.markTimeoutCertificateApplyScheduled(key, lastSnapshotHash, fromView, toView)
                          _ <- Async[F]
                            .start(
                              Temporal[F].sleep(config.viewChangeApplyDelay) >>
                                queue.offer(ConsensusCommand.CheckTimeoutCertificateApply(key, fromView, toView))
                            )
                            .void
                            .whenA(shouldSchedule)
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
              val mode = storage.viewSafetyMode(state.certifiedConsensusActive)
              if (mode == ViewSafetyMode.LegacyPreserve)
                applyCertifiedTimeoutCertificate(key, state, resources, fromView, toView, tc.reason)
              else
                applyCertifiedAfterLastChance(
                  key,
                  state,
                  resources,
                  fromView,
                  (outcome, reason) =>
                    Metrics[F].incrementCounter(
                      "dag_consensus_timeout_certificate_apply_total",
                      Seq(unsafeLabelName("outcome") -> outcome, unsafeLabelName("reason") -> reason)
                    ),
                  ConsensusCommand.CheckTimeoutCertificateApply(key, fromView, toView)
                ) { (latestState, latestResources) =>
                  applyCertifiedTimeoutCertificate(key, latestState, latestResources, fromView, toView, tc.reason)
                }
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
          val mode = storage.viewSafetyMode(state.certifiedConsensusActive)
          if (mode == ViewSafetyMode.LegacyPreserve)
            applyCertifiedViewChangePreservingLegacyDeferral(key, state, resources, fromView, toView)
          else
            applyCertifiedAfterLastChance(
              key,
              state,
              resources,
              fromView,
              (outcome, reason) =>
                Metrics[F].incrementCounter(
                  "dag_consensus_vcc_apply_total",
                  Seq(unsafeLabelName("outcome") -> outcome, unsafeLabelName("reason") -> reason)
                ),
              ConsensusCommand.CheckViewChangeApply(key, fromView, toView)
            ) { (latestState, latestResources) =>
              applyCertifiedViewChange(key, latestState, latestResources, fromView, toView)
            }
        }
    }

  private def applyCertifiedViewChangePreservingLegacyDeferral(
    key: Key,
    state: ConsensusState[Key, Status, Outcome, Kind],
    resources: ConsensusResources[Artifact, Kind],
    fromView: Long,
    toView: Long
  ): F[Unit] = {
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

  private def applyCertifiedAfterLastChance(
    key: Key,
    state: ConsensusState[Key, Status, Outcome, Kind],
    resources: ConsensusResources[Artifact, Kind],
    fromView: Long,
    meter: (String, String) => F[Unit],
    retryCommand: ConsensusCommand[Key, Artifact, Ctx, Outcome]
  )(
    applyTransition: (ConsensusState[Key, Status, Outcome, Kind], ConsensusResources[Artifact, Kind]) => F[Unit]
  ): F[Unit] = {
    val mode = storage.viewSafetyMode(state.certifiedConsensusActive)
    val phaseIndex = ctx.ops.phaseIndex(state.status)
    val currentKindHasCoreDeclarations = ctx.ops
      .maybeCollectingKind(state.status)
      .exists(kind =>
        resources.peerDeclarationsMap.exists {
          case (peerId, declarations) =>
            state.coreFacilitators.value.contains(peerId) && ctx.ops.kindGetter(kind)(declarations).isDefined
        }
      )
    val localProgress = phaseIndex == 2 || (ctx.ops.isProposalPhase(state.status) && currentKindHasCoreDeclarations)

    def scheduleOnce: F[Unit] =
      Async[F].start(Temporal[F].sleep(config.viewChangeApplyDelay / 2) >> queue.offer(retryCommand)).void

    def unlessLegacyVoteLocked(onUnlocked: => F[Unit]): F[Unit] =
      storage.getVoteLock(key).flatMap {
        case maybeLock if VoteLock.blocksLegacyViewChange(maybeLock, mode) =>
          meter("deferred_legacy_vote_lock", "artifact_signature_does_not_bind_proposal_value")
        case _ => onUnlocked
      }

    unlessLegacyVoteLocked {
      if (phaseIndex == 3 && mode == ViewSafetyMode.LegacyFreezeAfterVote)
        meter("deferred_binary_finality", "binary_signature_phase")
      else if (phaseIndex == 3 && mode == ViewSafetyMode.LegacyPreserve)
        meter("deferred_binary_finality", "binary_signature_phase_preserve_legacy") >> scheduleOnce
      else if (!localProgress) applyTransition(state, resources)
      else
        meter("last_chance_update", "proposal_or_majority_signature_in_progress") >>
          checkUpdate(key) >>
          storage.getState(key).flatMap {
            case None                                                  => meter("stale", "round_state_removed_after_last_chance")
            case Some(latest) if ctx.ops.isFinished(latest.status)     => meter("stale", "round_finished_after_last_chance")
            case Some(latest) if latest.viewNumber.toLong =!= fromView => meter("stale", "view_changed_after_last_chance")
            case Some(latest) =>
              val latestMode = storage.viewSafetyMode(latest.certifiedConsensusActive)
              val latestPhaseIndex = ctx.ops.phaseIndex(latest.status)
              if (latestPhaseIndex == 3 && latestMode == ViewSafetyMode.LegacyFreezeAfterVote)
                meter("deferred_binary_finality", "binary_signature_phase_after_last_chance")
              else if (latestPhaseIndex == 3 && latestMode == ViewSafetyMode.LegacyPreserve)
                meter("deferred_binary_finality", "binary_signature_phase_after_last_chance_preserve_legacy") >> scheduleOnce
              else if (latestPhaseIndex =!= phaseIndex)
                unlessLegacyVoteLocked(meter("deferred_phase_progress", s"phase_${phaseIndex}_to_$latestPhaseIndex") >> scheduleOnce)
              else unlessLegacyVoteLocked(storage.getResources(key).flatMap(applyTransition(latest, _)))
          }
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
                advanced <- storage.condModifyState[Boolean](key)(modify)
                didAdvance = advanced.getOrElse(false)
                _ <- StateTransitions.completeCertifiedAdvance(
                  didAdvance,
                  storage.pruneAttemptDeclarationsForView(key, toView),
                  queue.offer(ConsensusCommand.CheckUpdate(key)),
                  logQuorumShrinkApplied(key, "vcc_apply", shrinkDecision, votes.keySet) >>
                    ConsensusLog.info(
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
                    ) >> Metrics[F].incrementCounter(
                      "dag_consensus_vcc_apply_total",
                      Seq(unsafeLabelName("outcome") -> "advanced", unsafeLabelName("reason") -> "none")
                    ) >> Metrics[F].updateGauge("dag_consensus_view_number", toView),
                  Metrics[F].incrementCounter(
                    "dag_consensus_vcc_apply_total",
                    Seq(
                      unsafeLabelName("outcome") -> "not_advanced_race",
                      unsafeLabelName("reason") -> "state_already_advanced"
                    )
                  )
                )
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
                // Once the CAS commits, declaration pruning and serialized re-evaluation are protocol-critical. Telemetry below may fail
                // without stranding the new view.
                _ <- (storage.pruneAttemptDeclarationsForView(key, toView) >>
                  queue.offer(ConsensusCommand.CheckUpdate(key))).whenA(didAdvance)
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
                    // Certified signing-participation replacement is Core-attested. Core-target
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
                          queue.offer(CheckUpdate(key)) >>
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
          val requiresCoreAdmissionCertification =
            ctx.membershipPolicy.allowsCertifiedAtomicReplacement(state.certifiedConsensusActive)
          val q = AdmissionVoterPool.requiredQuorum(
            n,
            config.quorumThresholdFraction,
            requiresCoreAdmissionCertification
          )
          // Open expansion is certified by Core only: Tier 1 remains outside the liveness
          // machinery and cannot become necessary for committee growth. The legacy probation
          // path retains its wider deterministic witness pool. Under certified atomic membership,
          // however, every admission-only transition changes the Core-certified proposal value,
          // so probation ACS assembly is Core-certified too.
          val isProbationReadmission = ctx.probationPeersOf(state.lastOutcome).contains(target)
          val voterPool = AdmissionVoterPool.select(
            target,
            isProbationReadmission,
            requiresCoreAdmissionCertification,
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
  ): F[Unit] = {
    val activeKey = outcomeKey.get(outcome)
    val trigger = outcomeTrigger.get(outcome)

    // The last-outcome CAS and tokenized FSM notification are the only critical tail.
    // Queue is unbounded; masking this short Ref/CAS/offer sequence closes cancellation
    // between committing the outcome and releasing the Busy FSM. A retry observes
    // AlreadyCurrent and safely offers the same token again.
    Async[F].uncancelable { _ =>
      storage.tryUpdateLastConsensusOutcomeWithCleanup(prevKey, outcome).flatMap {
        case result @ (ConsensusStorage.OutcomeUpdateResult.Advanced | ConsensusStorage.OutcomeUpdateResult.AlreadyCurrent) =>
          storage.getRoundAttemptId.flatMap { expectedAttemptId =>
            queue
              .offer(ConsensusFinished(activeKey, outcome, trigger, expectedAttemptId))
              .as(result: ConsensusStorage.OutcomeUpdateResult)
          }
        case ConsensusStorage.OutcomeUpdateResult.Conflict =>
          Async[F].pure[ConsensusStorage.OutcomeUpdateResult](ConsensusStorage.OutcomeUpdateResult.Conflict)
      }
    }.flatMap {
      case ConsensusStorage.OutcomeUpdateResult.Advanced =>
        // Non-idempotent local accounting runs at most once. Every item is isolated so
        // observability/maintenance cannot suppress the already-enqueued completion.
        runOutcomeHook("finalized", outcome)(ctx.onOutcomeFinalized) >>
          ctx.peerQualityTracker.recordRoundSuccess(newState.facilitators.value.toSet).attempt.void >>
          ctx.nodeStorage.decrementJoiningGracePeriod.attempt.void >>
          finalizedRoundObservability(newState, outcome).attempt.void >>
          finalizedRoundIdempotentMaintenance(activeKey, outcome)

      case ConsensusStorage.OutcomeUpdateResult.AlreadyCurrent =>
        // A prior attempt committed the outcome but failed before its completion command
        // drained. Do not repeat peer-quality/grace counters; idempotent maintenance may resume.
        finalizedRoundIdempotentMaintenance(activeKey, outcome)

      case ConsensusStorage.OutcomeUpdateResult.Conflict =>
        // Another value already won. Remove only this conflicting round; the winning
        // tokenized completion (if local) remains responsible for releasing the FSM.
        (storage.cleanupConflictedRound(activeKey) >>
          Metrics[F].incrementCounter("dag_consensus_outcome_conflict") >>
          ConsensusLog.warn(
            log,
            Category.Lifecycle,
            activeKey.show,
            "n/a",
            LogEvent.OutcomeConflict,
            "reason" -> "same_key_different_outcome"
          )).attempt.void
    }
  }

  private def finalizedRoundIdempotentMaintenance(activeKey: Key, outcome: Outcome): F[Unit] =
    advancer.afterConsensusOutcomeCommitted(outcome).attempt.void >>
      storage.pruneStaleResources(activeKey).attempt.void >>
      ctx.clusterStorage.getResponsivePeers
        .flatMap(peers => storage.pruneStalePeerRegistrations(peers.iterator.map(_.id).toSet + ctx.selfId))
        .attempt
        .void >>
      ctx.nodeStorage.tryModifyStateGetResult(NodeState.WaitingForReady, NodeState.Ready).attempt.void

  private def finalizedRoundObservability(
    newState: ConsensusState[Key, Status, Outcome, Kind],
    outcome: Outcome
  ): F[Unit] = {
    val key = outcomeKey.get(outcome)
    val trigger = outcomeTrigger.get(outcome)
    val withdrawnCount = newState.withdrawnFacilitators.value.size
    val removedCount = newState.removedFacilitators.value.size
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

    for {
      now <- Async[F].monotonic
      duration = now - newState.createdAt
      leaderScore <- ctx.peerQualityTracker.getQualityScore(newState.leader)
      _ <- Metrics[F].recordTime("dag_consensus_duration", duration)
      _ <- Metrics[F].recordTimeHistogram("dag_consensus_duration", duration)
      _ <- Metrics[F].incrementCounter(
        "dag_consensus_outcome_finalized",
        Seq(unsafeLabelName("trigger_type") -> trigger.toString)
      )
      _ <- Metrics[F].incrementCounter(
        "dag_consensus_round_completed_total",
        Seq(
          unsafeLabelName("peer_id") -> ConsensusLog.pid(newState.leader),
          unsafeLabelName("trigger_type") -> trigger.toString
        )
      )
      _ <- Metrics[F].updateGauge("dag_consensus_round_facilitator_count", newState.facilitators.value.size)
      _ <- Metrics[F].updateGauge("dag_consensus_round_eligible_count", newState.eligibleFacilitators.value.size)
      _ <- ConsensusLog.info(
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
      )
      _ <- Metrics[F].updateGauge("dag_consensus_last_signer_count", signerCount.toLong)
      _ <- Metrics[F].updateGauge("dag_consensus_missing_active_signer_count", missingActiveSignerCount.toLong)
      _ <- Metrics[F].updateGauge("dag_consensus_missing_active_signer_ratio", missingActiveSignerRatio)
      _ <- Metrics[F].incrementCounter(
        "dag_consensus_outcome_signer_count_total",
        Seq(unsafeLabelName("signer_count") -> signerCount.toString)
      )
      _ <- Metrics[F].incrementCounter(
        "dag_consensus_outcome_signer_vs_active_total",
        Seq(
          unsafeLabelName("signer_count") -> signerCount.toString,
          unsafeLabelName("active_size") -> newState.facilitators.value.size.toString
        )
      )
      responders = newState.observedResponders.value.toSet
      committee = newState.roundStartFacilitators.value
      _ <-
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
    } yield ()
  }

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
    * `outcome_validation_failed` (post-retry artifact/context mismatch), `certified_outcome_validation_failed` (layer preflight rejected
    * missing/invalid certified lineage before mutation), `storage_init_failed` (trySetInitialConsensusOutcome returned false), `other`
    * (anything else). Read alongside `dag_consensus_init_download_failure_tracked` and `dag_consensus_force_leave_triggered` to identify
    * why a recovering peer ends up in Leaving.
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
      else if (msg.contains("downloaded_certified_outcome") || msg.contains("trusted_predecessor"))
        "certified_outcome_validation_failed"
      else if (msg.contains("Failed to initialize consensus storage")) "storage_init_failed"
      else "other"
  }

  def initFromDownload(key: Key, artifact: Signed[Artifact], context: Ctx, isRecovery: Boolean = false): F[Unit] =
    (for {
      _ <- ConsensusLog.info(log, Category.Lifecycle, key.toString, "n/a", LogEvent.DownloadInitStart)
      plannedCommittee <- ctx.plannedRecoveryCommittee
      // isRecoveryEffective = true if either the caller flagged this as recovery, OR the cluster
      // has advanced past our downloaded ordinal (peer returned a newer outcome). In both cases
      // we skip the 43s TimeTrigger deferral so the node joins the cluster immediately.
      (outcome, isRecoveryEffective) <- fetchOutcomeFromCluster(key, artifact, context, isRecovery, plannedCommittee)
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
              // Layer preflight is a trust boundary, not an after-initialization callback.
              // In certified consensus it verifies the peer-supplied outcome against an
              // independently trusted predecessor before any application or consensus
              // storage is mutated. Legacy layers keep the inert default hook.
              StateTransitions.validateDownloadBeforeMutation(
                ctx.onOutcomePreInitialize(o),
                (o, isRecoveryEffective).pure[F]
              )

            // If the specific-outcome endpoint reports Conflict, fetchOutcomeFromCluster falls
            // back to the peer's latest outcome. That can legitimately be N+1 after download
            // converged at N. Accepting it into consensus without also moving layer storage to
            // N+1 creates a torn handoff: consensus next emits N+2 while application storage
            // still requires N+1. Align the layer before installing the newer outcome.
            case StateTransitions.DownloadOutcomeDisposition.AcceptAndAlignApplicationStorage =>
              if (plannedCommittee.nonEmpty)
                new Throwable(
                  s"[DownloadInit] Recovery-plan validator requires exact anchor outcome for key=$key; peer returned newer key=${outcomeKey.get(o)}"
                ).raiseError[F, (Outcome, Boolean)]
              else
                StateTransitions.validateDownloadBeforeMutation(
                  ctx.onOutcomePreInitialize(o),
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
                )

            case StateTransitions.DownloadOutcomeDisposition.Reject =>
              new Throwable(
                s"[DownloadInit] Outcome validation failed after retries for key=$key: " +
                  s"keyMatch=$keyMatch, artifactMatch=$artifactMatch, contextMatch=$contextMatch"
              ).raiseError[F, (Outcome, Boolean)]
          }
        }
      // The layer preflight above has already authenticated the selected outcome before any
      // application-storage mutation. Arming here is therefore only a local scheduling hold;
      // it does not confer trust on the downloaded value.
      normalCommittee = plannedCommittee.fold(
        ctx.normalFirstRoundAlignment
          .flatMap(_.committeeOf(outcome))
          .filter(_.contains(ctx.selfId))
      )(_ => none[SortedSet[PeerId]])
      heldCommittee = plannedCommittee.orElse(normalCommittee)
      firstRoundPermit <- heldCommittee.traverse(_ => ctx.firstRoundStartGate.arm(outcomeKey.get(outcome)))
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
      initialized <- storage.trySetInitialConsensusOutcome(outcome)
      retainedOutcome <- storage.getLastConsensusOutcome
      _ <- new Throwable(s"[DownloadInit] Failed to initialize consensus storage")
        .raiseError[F, Unit]
        .unlessA(retainedOutcome.contains(outcome))
      // The cleanup is fail-fast and retryable. If it fails after the in-memory
      // outcome was installed, a later init attempt observes the same exact outcome,
      // re-runs this hook, and still cannot promote/start until it succeeds.
      _ <- ctx.onOutcomeSafetyInitialized(outcome)
      _ <- plannedCommittee.traverse_(committee =>
        ctx.onOutcomeRollbackInitialized(outcome, RollbackStartPolicy.RequireAlignedCommittee(committee))
      )
      _ <- runOutcomeHook("download_initialized", outcome)(ctx.onOutcomeInitialized).whenA(initialized)
      // A held first-round validator deliberately waits in WaitingForReady. The exact alignment
      // barrier is stronger than the legacy local promotion probe, and the first completed round
      // performs the existing WaitingForReady -> Ready transition.
      promoteToReady <- heldCommittee.fold(downloadReadyPromotionAllowed(outcome))(_ => false.pure[F])
      targetState: NodeState = if (promoteToReady) NodeState.Ready else NodeState.WaitingForReady
      // Initial download promotion is also the candidate-admission path. A peer exposes
      // its next-round registration key through `observationKey`; clearing it here makes
      // the peer visible as Ready but invisible to committee selection, so bootstrap can
      // collapse back to a singleton facilitator.
      _ <- storage.clearObservationKey.whenA(promoteToReady && isRecoveryEffective)
      _ <- heldCommittee.fold(ctx.nodeStorage.tryModifyState(NodeState.Observing, targetState)) { _ =>
        ctx.nodeStorage.getNodeState.flatMap { currentState =>
          StateTransitions.plannedInitializationStateDisposition(currentState) match {
            case StateTransitions.PlannedInitializationStateDisposition.EnterWaitingForReady =>
              ctx.nodeStorage.tryModifyState(NodeState.Observing, NodeState.WaitingForReady)
            case StateTransitions.PlannedInitializationStateDisposition.ResumeAndRepublish =>
              // A previous attempt may have updated the Ref before Topic publication failed. Publishing the accepted state again makes
              // peer lifecycle observation idempotent and lets the exact gate/barrier tail resume.
              ctx.nodeStorage.setNodeState(currentState)
            case StateTransitions.PlannedInitializationStateDisposition.Reject =>
              new IllegalStateException(
                s"[DownloadInit] Cannot resume planned initialization from node state=$currentState"
              ).raiseError[F, Unit]
          }
        }
      }
      installedState <- ctx.nodeStorage.getNodeState
      _ <- new IllegalStateException(
        s"[DownloadInit] Failed to establish planned node state: expected=$targetState actual=$installedState"
      ).raiseError[F, Unit]
        .unlessA(
          heldCommittee.isEmpty || installedState === targetState ||
            (targetState === NodeState.WaitingForReady && installedState === NodeState.Ready)
        )
      // Joining grace is a first-round prerequisite, not telemetry. Install it before starting
      // the barrier so an already-aligned fleet cannot race into aggressive timeouts.
      _ <- ctx.nodeStorage.setJoiningGracePeriod
      // Open an older generation only after the replacement outcome and lifecycle state have
      // both been installed successfully. Opening immediately after the remote fetch would let a
      // later probation/storage/state failure expose the stale parent to ordinary triggers.
      _ <- heldCommittee.fold(
        ctx.firstRoundStartGate.openIfSupersededBy(outcomeKey.get(outcome)).flatMap { opened =>
          Metrics[F]
            .incrementCounter(
              "dag_consensus_first_round_start_gate_superseded_total",
              Seq(unsafeLabelName("opened") -> opened.toString)
            )
            .attempt
            .void
        }
      )(_ => Async[F].unit)
      _ <- (plannedCommittee, firstRoundPermit).tupled.traverse {
        case (committee, permit) =>
          scheduleRecoveryPlanFirstRound(outcomeKey.get(outcome), outcome, committee, permit)
      }
      _ <- (normalCommittee, firstRoundPermit).tupled.traverse {
        case (committee, permit) =>
          scheduleNormalFirstRoundFollower(outcomeKey.get(outcome), outcome, committee, permit)
      }
      _ <- (Metrics[F].incrementCounter(
        "dag_consensus_init_download_target_state_total",
        Seq(unsafeLabelName("target_state") -> targetState.entryName)
      ) >> Metrics[F].incrementCounter(
        "dag_consensus_init_download_ready_promotion_total",
        Seq(unsafeLabelName("result") -> (if (promoteToReady) "promoted" else "waiting_for_ready"))
      )).attempt.void
      _ <- heldCommittee.fold {
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
      }(_ => Async[F].unit)
    } yield ())
      .flatTap(_ => initDownloadOutcome("success").attempt.void)
      .onError { case err => initDownloadOutcome(classifyInitDownloadError(err)).attempt.void }

  def initFromRollback(
    key: Key,
    outcome: Outcome,
    startPolicy: RollbackStartPolicy = RollbackStartPolicy.Immediate
  ): F[Unit] =
    for {
      _ <- ConsensusLog.info(log, Category.Lifecycle, key.toString, "n/a", LogEvent.RollbackInitStart).attempt.void
      firstRoundPermit <- startPolicy match {
        case RollbackStartPolicy.RequireAlignedCommittee(_) | RollbackStartPolicy.RequireOutcomeAlignedQuorum(_) =>
          ctx.firstRoundStartGate.arm(key).map(_.some)
        case _ => none[FirstRoundStartGate.Permit[Key]].pure[F]
      }
      _ <- ctx.onOutcomePreInitialize(outcome)
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
      _ <- ConsensusLog.info(log, Category.Lifecycle, key.toString, "n/a", LogEvent.RollbackStateCleared).attempt.void
      initialized <- storage.trySetInitialConsensusOutcome(outcome)
      _ <- runOutcomeHook("rollback_initialized", outcome)(ctx.onOutcomeInitialized).whenA(initialized)
      seededOutcome <- storage.getLastConsensusOutcome
      _ <- new IllegalStateException("Rollback initialization did not retain the requested outcome")
        .raiseError[F, Unit]
        .unlessA(seededOutcome.contains(outcome))
      _ <- ctx.onOutcomeSafetyInitialized(outcome)
      // This is safety state, not a sidecar. Run on every idempotent retry and propagate failure: the first-round barrier/start command must
      // never be armed until deleteAbove (or the layer-equivalent hook) has succeeded.
      _ <- ctx.onOutcomeRollbackInitialized(outcome, startPolicy)
      // Joining grace is a first-round prerequisite and must precede the barrier.
      _ <- ctx.nodeStorage.setJoiningGracePeriod
      // Install the plan barrier before post-install observability. Once the exact anchor and
      // joining grace exist, incidental logging/metrics failures cannot burn the generation.
      _ <- startPolicy match {
        case RollbackStartPolicy.RequireAlignedCommittee(committee) =>
          scheduleRecoveryPlanFirstRound(key, outcome, committee, firstRoundPermit.get)
        case RollbackStartPolicy.RequireOutcomeAlignedQuorum(committee) =>
          val required = math.max(1, QuorumPolicy.fromFraction(committee.size, config.quorumThresholdFraction))
          scheduleAlignedFirstRound(
            key,
            outcome,
            committee,
            StateTransitions.FirstRoundAlignmentRequirement.AtLeast(required),
            firstRoundPermit.get,
            mode = "normal_rollback"
          )
        case _ => Async[F].unit
      }
      _ <- (ConsensusLog.info(
        log,
        Category.Lifecycle,
        key.toString,
        "n/a",
        LogEvent.RollbackBootstrapActive,
        "mode" -> "checkpoint_server",
        "action" -> "serving_initial_outcome_before_first_round"
      ) >> Metrics[F].incrementCounter("dag_consensus_rollback_bootstrap_active_total")).attempt.void
      // GL0 full-network rollback orchestration starts one rollback node first, then
      // validators join and confirm the downloaded outcome. Deferring the first round
      // lets those validators reach Ready before readiness gates evaluate the cluster.
      _ <- startPolicy match {
        case RollbackStartPolicy.Immediate                      => queue.offer(StartRound(TimeTrigger.some))
        case RollbackStartPolicy.LegacyDeferred                 => scheduleRollbackFirstRound(key)
        case RollbackStartPolicy.RequireAlignedCommittee(_)     => Async[F].unit
        case RollbackStartPolicy.RequireOutcomeAlignedQuorum(_) => Async[F].unit
      }
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

  /** Shared fail-closed first-round barrier for normal and operator-authorized rollback committees.
    *
    * Unlike the legacy rollback delay, this never counts arbitrary Ready peers and has no elapsed-time escape. Each counted external member
    * must be in the current responsive cluster session, be Ready or WaitingForReady, and return the structurally exact seeded `Outcome`
    * from the authenticated specific-outcome endpoint. Structural equality is intentional: matching only key/artifact/context would miss
    * divergent operational fields, the same class of disagreement that previously produced different facilitator and reward derivations.
    * Operator recovery rechecks every member on every poll; only normal large-committee rollback caches exact responses for a peer's
    * current session.
    */
  private def scheduleAlignedFirstRound(
    key: Key,
    expectedOutcome: Outcome,
    committee: SortedSet[PeerId],
    requirement: StateTransitions.FirstRoundAlignmentRequirement,
    permit: FirstRoundStartGate.Permit[Key],
    mode: String
  ): F[Unit] = {
    val pollInterval = 1.second
    val perPeerTimeout = 3.seconds
    val normalRollback = mode === "normal_rollback"

    def fetchAlignment(peer: Peer): F[(PeerId, StateTransitions.RecoveryPlanPeerOutcome)] =
      Temporal[F]
        .timeoutTo(
          ctx.consensusClient
            .getSpecificConsensusOutcome(GetConsensusOutcomeRequest(key))
            .run(peer)
            .map(outcome => StateTransitions.recoveryPlanPeerOutcome(expectedOutcome, outcome)),
          perPeerTimeout,
          (StateTransitions.RecoveryPlanPeerOutcome.FetchFailed: StateTransitions.RecoveryPlanPeerOutcome).pure[F]
        )
        .handleErrorWith { err =>
          if (normalRollback)
            (StateTransitions.RecoveryPlanPeerOutcome.FetchFailed: StateTransitions.RecoveryPlanPeerOutcome).pure[F]
          else
            ConsensusLog
              .warn(
                log,
                Category.Lifecycle,
                key.toString,
                "n/a",
                LogEvent.RollbackFirstRoundDeferred,
                "mode" -> mode,
                "peer" -> ConsensusLog.pid(peer.id),
                "reason" -> "outcome_fetch_failed",
                "error" -> Option(err.getMessage).getOrElse(err.getClass.getSimpleName)
              )
              .as(StateTransitions.RecoveryPlanPeerOutcome.FetchFailed)
        }
        .tupleLeft(peer.id)

    def fetchUncached(peers: List[Peer]): F[List[(PeerId, StateTransitions.RecoveryPlanPeerOutcome)]] =
      if (normalRollback)
        peers.traverse(peer => Async[F].start(fetchAlignment(peer))).flatMap(_.traverse(_.joinWithNever))
      else peers.traverse(fetchAlignment)

    def inspect(
      alignedSessions: Ref[F, Map[PeerId, Peer]]
    ): F[StateTransitions.RecoveryPlanBarrierStatus] =
      (ctx.nodeStorage.getNodeState, ctx.clusterStorage.getResponsivePeers).flatMapN { (nodeState, peers) =>
        val peerById = peers.iterator.map(peer => peer.id -> peer).toMap
        val expectedExternal = committee - ctx.selfId
        val fetchable = expectedExternal.toList.flatMap(peerById.get).filter { peer =>
          peer.state === NodeState.Ready || peer.state === NodeState.WaitingForReady
        }

        alignedSessions.modify { cached =>
          // Cache only the normal large-committee quorum path. The explicit operator-recovery
          // path intentionally preserves its existing stronger semantics: every named member
          // must be observed exact on the current poll, not merely earlier in the same session.
          val current =
            if (normalRollback)
              cached.filter {
                case (peerId, peer) =>
                  peerById.get(peerId).exists { currentPeer =>
                    currentPeer.clusterSession === peer.clusterSession &&
                    (currentPeer.state === NodeState.Ready || currentPeer.state === NodeState.WaitingForReady)
                  }
              }
            else Map.empty[PeerId, Peer]
          val cachedIds = current.keySet
          current -> (current, fetchable.filterNot(peer => cachedIds.contains(peer.id)))
        }.flatMap {
          case (cached, uncached) =>
            fetchUncached(uncached).flatMap { fetched =>
              val newlyAligned = fetched.collect {
                case (peerId, StateTransitions.RecoveryPlanPeerOutcome.Aligned) => peerId
              }.toSet
              val fetchedPeerById = uncached.iterator.map(peer => peer.id -> peer).toMap
              val additions = newlyAligned.flatMap(peerId => fetchedPeerById.get(peerId).map(peerId -> _)).toMap
              val peerOutcomes = fetched.foldLeft(
                cached.keySet.iterator
                  .map(_ -> (StateTransitions.RecoveryPlanPeerOutcome.Aligned: StateTransitions.RecoveryPlanPeerOutcome))
                  .toMap
              ) {
                case (outcomes, (peerId, outcome)) => outcomes.updated(peerId, outcome)
              }

              (if (normalRollback)
                 alignedSessions.update { current =>
                   additions.foldLeft(current) {
                     case (sessions, (peerId, peer)) => sessions.updated(peerId, peer)
                   }
                 }
               else Async[F].unit) >>
                StateTransitions
                  .firstRoundAlignmentBarrierStatus(
                    selfId = ctx.selfId,
                    committee = committee,
                    requirement = requirement,
                    selfReady = nodeState === NodeState.Ready || nodeState === NodeState.WaitingForReady,
                    responsivePeerStates = peerById.view.mapValues(_.state).toMap,
                    peerOutcomes = peerOutcomes
                  )
                  .pure[F]
            }
        }
      }

    def ids(peers: Iterable[PeerId]): String =
      peers.iterator.map(ConsensusLog.pid).toList.sorted.mkString(",")

    def record(status: StateTransitions.RecoveryPlanBarrierStatus, attempt: Long): F[Unit] = {
      val normalOutcome =
        if (status.aligned) "aligned"
        else if (status.invalidCommittee) "invalid_committee"
        else if (status.mismatchedOutcome.nonEmpty) "mismatch"
        else if (status.fetchFailed.nonEmpty) "fetch_failed"
        else if (status.missingOutcome.nonEmpty) "missing_outcome"
        else if (status.invalidState.nonEmpty) "invalid_state"
        else if (status.missingSession.nonEmpty) "missing_session"
        else "below_quorum"

      val logStatus =
        if (!normalRollback && status.aligned)
          ConsensusLog.info(
            log,
            Category.Lifecycle,
            key.toString,
            "n/a",
            LogEvent.RollbackQuorumFeasible,
            "mode" -> mode,
            "reason" -> "exact_planned_committee_aligned",
            "committee" -> ids(committee),
            "alignedPeers" -> ids(status.alignedPeers),
            "attempt" -> attempt.toString
          )
        else if (!normalRollback && (attempt === 1L || attempt % 5L === 0L))
          ConsensusLog.warn(
            log,
            Category.Lifecycle,
            key.toString,
            "n/a",
            LogEvent.RollbackFirstRoundDeferred,
            "mode" -> mode,
            "reason" -> "planned_committee_not_aligned",
            "attempt" -> attempt.toString,
            "selfReady" -> status.selfReady.toString,
            "invalidCommittee" -> status.invalidCommittee.toString,
            "expectedExternal" -> ids(status.expectedExternal),
            "alignedPeers" -> ids(status.alignedPeers),
            "missingSession" -> ids(status.missingSession),
            "invalidState" -> status.invalidState.iterator.map { case (pid, state) => s"${ConsensusLog.pid(pid)}=$state" }.mkString(","),
            "missingOutcome" -> ids(status.missingOutcome),
            "mismatchedOutcome" -> ids(status.mismatchedOutcome),
            "fetchFailed" -> ids(status.fetchFailed)
          )
        else if (normalRollback && status.aligned)
          ConsensusLog.info(
            log,
            Category.Lifecycle,
            key.toString,
            "n/a",
            LogEvent.RollbackQuorumFeasible,
            "mode" -> mode,
            "reason" -> "exact_committee_quorum_aligned",
            "committeeSize" -> committee.size.toString,
            "alignedCount" -> status.alignedCount.toString,
            "required" -> status.required.toString,
            "attempt" -> attempt.toString
          )
        else if (normalRollback && (attempt === 1L || attempt % 5L === 0L))
          ConsensusLog.warn(
            log,
            Category.Lifecycle,
            key.toString,
            "n/a",
            LogEvent.RollbackFirstRoundDeferred,
            "mode" -> mode,
            "reason" -> "anchor_committee_quorum_not_aligned",
            "attempt" -> attempt.toString,
            "selfReady" -> status.selfReady.toString,
            "invalidCommittee" -> status.invalidCommittee.toString,
            "committeeSize" -> committee.size.toString,
            "alignedCount" -> status.alignedCount.toString,
            "required" -> status.required.toString,
            "deficit" -> status.deficit.toString,
            "missingSessionCount" -> status.missingSession.size.toString,
            "invalidStateCount" -> status.invalidState.size.toString,
            "missingOutcomeCount" -> status.missingOutcome.size.toString,
            "mismatchedOutcomeCount" -> status.mismatchedOutcome.size.toString,
            "fetchFailedCount" -> status.fetchFailed.size.toString
          )
        else Async[F].unit

      val metrics =
        if (normalRollback)
          Metrics[F].incrementCounter(
            "dag_consensus_normal_first_round_alignment_poll_total",
            Seq(unsafeLabelName("outcome") -> normalOutcome)
          ) >>
            Metrics[F].updateGauge("dag_consensus_normal_first_round_expected_committee_size", committee.size.toLong) >>
            Metrics[F].updateGauge("dag_consensus_normal_first_round_required_count", status.required.toLong) >>
            Metrics[F].updateGauge("dag_consensus_normal_first_round_aligned_count", status.alignedCount.toLong) >>
            Metrics[F].updateGauge("dag_consensus_normal_first_round_alignment_deficit", status.deficit.toLong)
        else
          Metrics[F].incrementCounter(
            "dag_consensus_recovery_plan_alignment_poll_total",
            Seq(unsafeLabelName("aligned") -> status.aligned.toString)
          ) >>
            Metrics[F].updateGauge("dag_consensus_recovery_plan_alignment_missing_session", status.missingSession.size.toLong) >>
            Metrics[F].updateGauge("dag_consensus_recovery_plan_alignment_invalid_state", status.invalidState.size.toLong) >>
            Metrics[F].updateGauge("dag_consensus_recovery_plan_alignment_missing_outcome", status.missingOutcome.size.toLong) >>
            Metrics[F].updateGauge("dag_consensus_recovery_plan_alignment_mismatched_outcome", status.mismatchedOutcome.size.toLong) >>
            Metrics[F].updateGauge("dag_consensus_recovery_plan_alignment_fetch_failed", status.fetchFailed.size.toLong)

      logStatus >> metrics
    }

    def reportFailure(stage: String, err: Throwable): F[Unit] =
      // Reporting is deliberately best-effort: neither a logger nor metrics backend failure may
      // terminate the fail-closed alignment barrier that is guarding the first consensus round.
      log
        .error(err)(
          s"First-round alignment barrier mode=$mode stage=$stage failed at key=$key; retrying without timeout escape"
        )
        .attempt
        .void >>
        (if (normalRollback)
           Metrics[F].incrementCounter(
             "dag_consensus_normal_first_round_alignment_error_total",
             Seq(unsafeLabelName("stage") -> stage)
           )
         else
           Metrics[F].incrementCounter(
             "dag_consensus_recovery_plan_alignment_error_total",
             Seq(unsafeLabelName("stage") -> stage)
           )).attempt.void

    val announce =
      if (normalRollback)
        ConsensusLog.info(
          log,
          Category.Lifecycle,
          key.toString,
          "n/a",
          LogEvent.RollbackFirstRoundDeferred,
          "mode" -> mode,
          "pollInterval" -> pollInterval.toString,
          "perPeerTimeout" -> perPeerTimeout.toString,
          "maxDelay" -> "none",
          "committeeSize" -> committee.size.toString,
          "required" -> requirement.required(committee.size).toString
        )
      else
        ConsensusLog.info(
          log,
          Category.Lifecycle,
          key.toString,
          "n/a",
          LogEvent.RollbackFirstRoundDeferred,
          "mode" -> mode,
          "pollInterval" -> pollInterval.toString,
          "perPeerTimeout" -> perPeerTimeout.toString,
          "maxDelay" -> "none",
          "committee" -> ids(committee)
        )

    val initialMetrics =
      if (normalRollback)
        Metrics[F].updateGauge("dag_consensus_normal_first_round_alignment_held", 1L)
      else Metrics[F].incrementCounter("dag_consensus_recovery_plan_first_round_deferred_total")

    for {
      alignedSessions <- Ref.of[F, Map[PeerId, Peer]](Map.empty)
      startedAt <- Temporal[F].monotonic
      // Publish the hold before returning from initialization. Monitoring must never mistake
      // this intentional synchronization window for an ordinary flat-tip stall.
      _ <- initialMetrics.handleErrorWith(err => reportFailure("initial_metrics", err))
      _ <- Async[F]
        .start(
          announce.handleErrorWith(err => reportFailure("initial_record", err)) >>
            StateTransitions.runFirstRoundAlignmentLoop(
              inspect(alignedSessions),
              (status: StateTransitions.RecoveryPlanBarrierStatus) => status.aligned,
              record,
              Temporal[F].sleep(pollInterval),
              queue.offer(ReleaseFirstRoundStart(permit, committee)),
              ctx.firstRoundStartGate.isPending(permit),
              reportFailure
            ) >>
            (if (normalRollback)
               ctx.firstRoundStartGate.isHeld.flatMap {
                 case true => Async[F].unit
                 case false =>
                   Temporal[F].monotonic.flatMap { finishedAt =>
                     Metrics[F].updateGauge("dag_consensus_normal_first_round_alignment_held", 0L) >>
                       Metrics[F].incrementCounter(
                         "dag_consensus_normal_first_round_release_total",
                         Seq(unsafeLabelName("role") -> "lead", unsafeLabelName("reason") -> "aligned_quorum")
                       ) >>
                       Metrics[F].recordTimeHistogram("dag_consensus_normal_first_round_wait", finishedAt - startedAt)
                   }
               }
             else Async[F].unit)
        )
        .void
    } yield ()
  }

  private def scheduleRecoveryPlanFirstRound(
    key: Key,
    expectedOutcome: Outcome,
    committee: SortedSet[PeerId],
    permit: FirstRoundStartGate.Permit[Key]
  ): F[Unit] =
    scheduleAlignedFirstRound(
      key,
      expectedOutcome,
      committee,
      StateTransitions.FirstRoundAlignmentRequirement.AllMembers,
      permit,
      mode = "operator_recovery_plan"
    )

  /** Normal post-bootstrap validator release path.
    *
    * A held validator does not run its own first-round timer. It waits until an ordinary Facility for `key.next` has been stored from a
    * current-session member of the expected committee, validates the Facility through the layer policy, and confirms that origin's latest
    * typed outcome is still the exact installed parent. The Facility is only a timing pulse: the serialized release still derives the
    * complete round locally and enforces the expected committee before any local Facility effect can commit.
    */
  private def scheduleNormalFirstRoundFollower(
    key: Key,
    expectedOutcome: Outcome,
    committee: SortedSet[PeerId],
    permit: FirstRoundStartGate.Permit[Key]
  ): F[Unit] =
    ctx.normalFirstRoundAlignment match {
      case None =>
        new IllegalStateException("Normal first-round follower was armed without a layer alignment policy").raiseError[F, Unit]

      case Some(policy) =>
        val pollInterval = 1.second
        val perPeerTimeout = 3.seconds
        val nextKey = key.next

        final case class PulseInspection(
          status: StateTransitions.NormalFirstRoundPulseStatus,
          invalidFacilityCount: Int,
          recoveryAlreadyTriggered: Boolean
        )

        def latestOutcome(peer: Peer): F[(PeerId, StateTransitions.NormalFirstRoundPulsePeerOutcome)] =
          Temporal[F]
            .timeoutTo(
              ctx.consensusClient.getLatestConsensusOutcome.run(peer).map[StateTransitions.NormalFirstRoundPulsePeerOutcome] {
                case Some(outcome) if outcome === expectedOutcome     => StateTransitions.NormalFirstRoundPulsePeerOutcome.Aligned
                case Some(outcome) if outcomeKey.get(outcome) =!= key => StateTransitions.NormalFirstRoundPulsePeerOutcome.Ahead
                case Some(_)                                          => StateTransitions.NormalFirstRoundPulsePeerOutcome.Mismatched
                case None                                             => StateTransitions.NormalFirstRoundPulsePeerOutcome.Missing
              },
              perPeerTimeout,
              (StateTransitions.NormalFirstRoundPulsePeerOutcome.FetchFailed: StateTransitions.NormalFirstRoundPulsePeerOutcome).pure[F]
            )
            .handleError(_ => StateTransitions.NormalFirstRoundPulsePeerOutcome.FetchFailed)
            .tupleLeft(peer.id)

        def inspect(recoveryTriggered: Ref[F, Boolean]): F[PulseInspection] =
          (ctx.clusterStorage.getResponsivePeers, storage.getResources(nextKey), storage.getPeerCurrentKeys).flatMapN {
            (peers, resources, peerCurrentKeys) =>
              val peerById = peers.iterator.map(peer => peer.id -> peer).toMap
              val committeeFacilities = resources.peerDeclarationsMap.iterator.collect {
                case (peerId, declarations) if committee.contains(peerId) =>
                  declarations.facility.map(peerId -> _)
              }.flatten.toMap
              val matchingOrigins = committeeFacilities.collect {
                case (peerId, facility) if policy.facilityMatches(key, expectedOutcome, facility) => peerId
              }.toSet
              val invalidFacilityCount = committeeFacilities.size - matchingOrigins.size
              // A node that starts alone while the chain is already moving may miss the K+1
              // Facility pulse. An authenticated declaration at K+2 or later proves only that the
              // origin is worth querying; it never releases the gate by itself. The typed latest
              // outcome must still prove that recovery, rather than stale-round start, is required.
              val futureDeclarationOrigins = peerCurrentKeys.collect {
                case (peerId, observedKey) if committee.contains(peerId) && observedKey =!= key && observedKey =!= nextKey => peerId
              }.toSet
              val matchingPeers = matchingOrigins.toList.flatMap(peerById.get).filter { peer =>
                peer.state === NodeState.Ready || peer.state === NodeState.WaitingForReady
              }
              val aheadPeers = futureDeclarationOrigins.toList.flatMap(peerById.get).filter { peer =>
                peer.state === NodeState.Ready || peer.state === NodeState.WaitingForReady
              }
              // One typed corroboration is sufficient for either local action. Sampling one
              // current-session origin per poll avoids an O(N^2) HTTP burst when a large committee
              // gossips all of its Facilities at once. Future-key evidence takes precedence so a
              // late member cannot release an old round merely because a straggler still serves K.
              val probeCandidates = if (futureDeclarationOrigins.nonEmpty) aheadPeers else matchingPeers
              val observeOne =
                if (probeCandidates.isEmpty) List.empty[(PeerId, StateTransitions.NormalFirstRoundPulsePeerOutcome)].pure[F]
                else Random[F].elementOf(probeCandidates).flatMap(latestOutcome).map(List(_))

              (observeOne, recoveryTriggered.get).mapN { (observed, alreadyTriggered) =>
                PulseInspection(
                  StateTransitions.normalFirstRoundPulseStatus(
                    committee,
                    matchingOrigins,
                    futureDeclarationOrigins,
                    peerById.view.mapValues(_.state).toMap,
                    observed.toMap
                  ),
                  invalidFacilityCount,
                  alreadyTriggered
                )
              }
          }

        def triggerPeerAheadRecovery(origin: PeerId, triggered: Ref[F, Boolean]): F[Unit] =
          triggered.get.ifM(
            Async[F].unit,
            (
              ConsensusLog.warn(
                log,
                Category.Recovery,
                key.show,
                "n/a",
                LogEvent.RollbackFirstRoundDeferred,
                "mode" -> "normal_rollback_follower",
                "reason" -> "pulse_origin_already_ahead",
                "origin" -> ConsensusLog.pid(origin),
                "action" -> "reenter_recovery_download"
              ) >>
                ctx.nodeStorage.setRecoveryDownload >>
                ctx.nodeStorage.getNodeState.flatMap {
                  case NodeState.WaitingForReady =>
                    ctx.nodeStorage.tryModifyState(NodeState.WaitingForReady, NodeState.WaitingForDownload)
                  case NodeState.Ready     => ctx.nodeStorage.tryModifyState(NodeState.Ready, NodeState.WaitingForDownload)
                  case NodeState.Observing => ctx.nodeStorage.tryModifyState(NodeState.Observing, NodeState.WaitingForDownload)
                  case _                   => Async[F].unit
                }
            ) >> triggered.set(true)
          )

        def record(triggered: Ref[F, Boolean])(inspection: PulseInspection, attempt: Long): F[Unit] = {
          val status = inspection.status
          val maybeAhead = status.aheadOrigin.traverse_(triggerPeerAheadRecovery(_, triggered))
          val logStatus =
            if (status.releaseOrigin.nonEmpty || attempt === 1L || attempt % 5L === 0L)
              ConsensusLog.info(
                log,
                Category.Lifecycle,
                key.show,
                "n/a",
                LogEvent.RollbackFirstRoundDeferred,
                "mode" -> "normal_rollback_follower",
                "outcome" -> status.outcomeLabel,
                "attempt" -> attempt.toString,
                "committeeSize" -> committee.size.toString,
                "matchingFacilityOrigins" -> status.matchingFacilityOrigins.size.toString,
                "invalidFacilities" -> inspection.invalidFacilityCount.toString,
                "releaseOrigin" -> status.releaseOrigin.fold("none")(ConsensusLog.pid),
                "aheadOrigin" -> status.aheadOrigin.fold("none")(ConsensusLog.pid)
              )
            else Async[F].unit

          logStatus >>
            Metrics[F].incrementCounter(
              "dag_consensus_normal_first_round_pulse_total",
              Seq(unsafeLabelName("outcome") -> status.outcomeLabel)
            ) >> maybeAhead
        }

        def reportFailure(stage: String, err: Throwable): F[Unit] =
          (log.error(err)(
            s"Normal first-round follower pulse stage=$stage failed at key=$key; retrying without timeout escape"
          ) >> Metrics[F].incrementCounter(
            "dag_consensus_normal_first_round_alignment_error_total",
            Seq(unsafeLabelName("stage") -> s"follower_$stage")
          )).attempt.void

        for {
          peerAheadTriggered <- Ref.of[F, Boolean](false)
          startedAt <- Temporal[F].monotonic
          _ <- Metrics[F].updateGauge("dag_consensus_normal_first_round_alignment_held", 1L).attempt.void
          _ <- Metrics[F]
            .updateGauge("dag_consensus_normal_first_round_expected_committee_size", committee.size.toLong)
            .attempt
            .void
          _ <- Metrics[F]
            .updateGauge(
              "dag_consensus_normal_first_round_required_count",
              math.max(1, QuorumPolicy.fromFraction(committee.size, config.quorumThresholdFraction)).toLong
            )
            .attempt
            .void
          _ <- Async[F]
            .start(
              StateTransitions.runFirstRoundAlignmentLoop(
                inspect(peerAheadTriggered),
                (inspection: PulseInspection) =>
                  StateTransitions.shouldReleaseNormalFirstRoundPulse(
                    inspection.status,
                    inspection.recoveryAlreadyTriggered
                  ),
                record(peerAheadTriggered),
                Temporal[F].sleep(pollInterval),
                queue.offer(ReleaseFirstRoundStart(permit, committee)),
                ctx.firstRoundStartGate.isPending(permit),
                reportFailure
              ) >>
                ctx.firstRoundStartGate.isHeld.flatMap {
                  case true => Async[F].unit
                  case false =>
                    peerAheadTriggered.get.flatMap {
                      case true =>
                        // A newer validated initialization superseded this permit. It did not
                        // release the stale first round, so do not report a Facility-pulse release.
                        Metrics[F].updateGauge("dag_consensus_normal_first_round_alignment_held", 0L)
                      case false =>
                        Temporal[F].monotonic.flatMap { finishedAt =>
                          Metrics[F].updateGauge("dag_consensus_normal_first_round_alignment_held", 0L) >>
                            Metrics[F].incrementCounter(
                              "dag_consensus_normal_first_round_release_total",
                              Seq(unsafeLabelName("role") -> "validator", unsafeLabelName("reason") -> "facility_pulse")
                            ) >>
                            Metrics[F].recordTimeHistogram("dag_consensus_normal_first_round_wait", finishedAt - startedAt)
                        }
                    }
                }
            )
            .void
        } yield ()
    }

  private def fetchOutcomeFromCluster(
    key: Key,
    artifact: Signed[Artifact],
    context: Ctx,
    isRecovery: Boolean,
    plannedCommittee: Option[SortedSet[PeerId]]
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
        val inPlan = (peer: Peer) => plannedCommittee.forall(_.contains(peer.id)) && peer.id =!= ctx.selfId
        val primaryCandidates = allPeers
          .filter(p => inPlan(p) && (p.state == NodeState.Ready || p.state == NodeState.WaitingForReady))
          .toSeq
        val observingPeers = allPeers.filter(p => inPlan(p) && p.state == NodeState.Observing).toSeq

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
              s"No ${plannedCommittee.fold("fleet")(c => s"planned(${c.size})")} peers in Ready, WaitingForReady, or Observing state. " +
                s"Available: ${allPeers.size} peers"
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
            case _: org.http4s.client.UnexpectedStatus if plannedCommittee.isEmpty =>
              ctx.consensusClient.getLatestConsensusOutcome.run(peer)
            case _: org.http4s.client.UnexpectedStatus => none[Outcome].pure[F]
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
        exactMatch || (isRecovery && plannedCommittee.isEmpty)
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

  /** Enforce the downloaded-outcome trust boundary before any application or consensus mutation.
    *
    * Kept generic so every L0 layer shares the same sequencing invariant and tests can prove that a failed layer preflight never evaluates
    * the mutation effect.
    */
  private[consensus] def validateDownloadBeforeMutation[F[_]: Monad, A](
    validate: F[Unit],
    mutate: => F[A]
  ): F[A] =
    validate >> mutate

  private[consensus] sealed trait PlannedInitializationStateDisposition
  private[consensus] object PlannedInitializationStateDisposition {
    case object EnterWaitingForReady extends PlannedInitializationStateDisposition
    case object ResumeAndRepublish extends PlannedInitializationStateDisposition
    case object Reject extends PlannedInitializationStateDisposition
  }

  /** Planned initialization has a non-transactional lifecycle tail. Retrying its exact signed generation must therefore accept the states
    * that tail may already have installed, while every unrelated lifecycle state remains fail-closed.
    */
  private[consensus] def plannedInitializationStateDisposition(
    state: NodeState
  ): PlannedInitializationStateDisposition =
    state match {
      case NodeState.Observing                         => PlannedInitializationStateDisposition.EnterWaitingForReady
      case NodeState.WaitingForReady | NodeState.Ready => PlannedInitializationStateDisposition.ResumeAndRepublish
      case _                                           => PlannedInitializationStateDisposition.Reject
    }

  private[consensus] def completeCertifiedAdvance[F[_]: Async](
    didAdvance: Boolean,
    prune: F[Unit],
    enqueueCheckUpdate: F[Unit],
    advancedObservability: F[Unit],
    notAdvancedObservability: F[Unit]
  ): F[Unit] =
    if (didAdvance) prune >> enqueueCheckUpdate >> advancedObservability.attempt.void
    else notAdvancedObservability.attempt.void

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

  private[consensus] sealed trait RecoveryPlanPeerOutcome

  private[consensus] object RecoveryPlanPeerOutcome {
    case object Aligned extends RecoveryPlanPeerOutcome
    case object Missing extends RecoveryPlanPeerOutcome
    case object Mismatched extends RecoveryPlanPeerOutcome
    case object FetchFailed extends RecoveryPlanPeerOutcome
  }

  private[consensus] sealed trait NormalFirstRoundPulsePeerOutcome

  private[consensus] object NormalFirstRoundPulsePeerOutcome {
    case object Aligned extends NormalFirstRoundPulsePeerOutcome
    case object Ahead extends NormalFirstRoundPulsePeerOutcome
    case object Missing extends NormalFirstRoundPulsePeerOutcome
    case object Mismatched extends NormalFirstRoundPulsePeerOutcome
    case object FetchFailed extends NormalFirstRoundPulsePeerOutcome
  }

  private[consensus] final case class NormalFirstRoundPulseStatus(
    matchingFacilityOrigins: SortedSet[PeerId],
    alignedOrigins: SortedSet[PeerId],
    aheadOrigins: SortedSet[PeerId],
    missingSession: SortedSet[PeerId],
    invalidState: SortedMap[PeerId, NodeState],
    missingOutcome: SortedSet[PeerId],
    mismatchedOutcome: SortedSet[PeerId],
    fetchFailed: SortedSet[PeerId]
  ) {
    val aheadOrigin: Option[PeerId] = aheadOrigins.headOption
    // A peer already beyond the installed parent is stronger evidence than another peer still
    // serving it. Prefer normal catch-up and never open a stale first round in that mixed view.
    val releaseOrigin: Option[PeerId] = Option.when(aheadOrigin.isEmpty)(alignedOrigins.headOption).flatten

    val outcomeLabel: String =
      if (aheadOrigin.nonEmpty) "peer_ahead"
      else if (releaseOrigin.nonEmpty) "aligned"
      else if (mismatchedOutcome.nonEmpty) "mismatch"
      else if (fetchFailed.nonEmpty) "fetch_failed"
      else if (missingOutcome.nonEmpty) "missing_outcome"
      else if (invalidState.nonEmpty) "invalid_state"
      else if (missingSession.nonEmpty) "missing_session"
      else "waiting_for_facility"
  }

  /** Process-local threshold for the shared exact-outcome first-round barrier.
    *
    * Recovery overrides retain their stronger all-member requirement. Normal post-bootstrap rollback uses the protocol quorum derived by
    * the caller. Neither requirement changes a consensus quorum; it only determines when the local first-round gate may be released.
    */
  private[consensus] sealed trait FirstRoundAlignmentRequirement {
    def required(committeeSize: Int): Int
    def validFor(committeeSize: Int): Boolean
  }

  private[consensus] object FirstRoundAlignmentRequirement {
    case object AllMembers extends FirstRoundAlignmentRequirement {
      def required(committeeSize: Int): Int = committeeSize
      def validFor(committeeSize: Int): Boolean = CommitteeViability.supportsCoordination(committeeSize)
    }

    final case class AtLeast(value: Int) extends FirstRoundAlignmentRequirement {
      def required(committeeSize: Int): Int = value
      def validFor(committeeSize: Int): Boolean = committeeSize > 0 && value > 0 && value <= committeeSize
    }
  }

  private[consensus] final case class RecoveryPlanBarrierStatus(
    requirement: FirstRoundAlignmentRequirement,
    committeeSize: Int,
    selfReady: Boolean,
    invalidCommittee: Boolean,
    expectedExternal: SortedSet[PeerId],
    alignedPeers: SortedSet[PeerId],
    missingSession: SortedSet[PeerId],
    invalidState: SortedMap[PeerId, NodeState],
    missingOutcome: SortedSet[PeerId],
    mismatchedOutcome: SortedSet[PeerId],
    fetchFailed: SortedSet[PeerId]
  ) {
    val required: Int = requirement.required(committeeSize)
    val alignedCount: Int = alignedPeers.size + (if (selfReady && !invalidCommittee) 1 else 0)
    val deficit: Int = math.max(0, required - alignedCount)
    val aligned: Boolean =
      selfReady &&
        !invalidCommittee &&
        alignedCount >= required
  }

  private[consensus] def recoveryPlanPeerOutcome[Outcome: Eq](
    expected: Outcome,
    observed: Option[Outcome]
  ): RecoveryPlanPeerOutcome =
    observed match {
      case Some(outcome) if outcome === expected => RecoveryPlanPeerOutcome.Aligned
      case Some(_)                               => RecoveryPlanPeerOutcome.Mismatched
      case None                                  => RecoveryPlanPeerOutcome.Missing
    }

  /** Run a fail-closed alignment barrier until the generation-bound gate acknowledges that the exact first round was established.
    *
    * Every operational effect is failure-contained. An inspection, observation record, sleep, queue offer, logger, or metrics failure may
    * delay recovery, but cannot silently kill the barrier fiber and leave a healthy aligned committee parked forever. A successful queue
    * offer is not an acknowledgement: the loop remains alive until `startPending` becomes false. Cancellation remains cancellation;
    * ordinary raised errors are reported best-effort and retried without an elapsed-time escape.
    */
  private[consensus] def runFirstRoundAlignmentLoop[F[_]: Temporal, A](
    inspect: F[A],
    isAligned: A => Boolean,
    record: (A, Long) => F[Unit],
    pause: F[Unit],
    offerStart: F[Unit],
    startPending: F[Boolean],
    reportFailure: (String, Throwable) => F[Unit]
  ): F[Unit] = {
    def report(stage: String, err: Throwable): F[Unit] =
      reportFailure(stage, err).attempt.void

    def loop(attempt: Long): F[Unit] = {
      def pauseThenRetry: F[Unit] =
        pause
          .handleErrorWith(err => report("sleep", err) >> Temporal[F].cede) >>
          loop(attempt + 1L)

      def continueUntilReleased: F[Unit] =
        startPending.attempt.flatMap {
          case Right(false) => Temporal[F].unit
          case Right(true)  => pauseThenRetry
          case Left(err)    => report("release_status", err) >> pauseThenRetry
        }

      startPending.attempt.flatMap {
        case Right(false) => Temporal[F].unit
        case Left(err)    => report("release_status", err) >> pauseThenRetry
        case Right(true) =>
          inspect.attempt.flatMap {
            case Left(err) =>
              report("inspect", err) >> pauseThenRetry
            case Right(status) =>
              record(status, attempt).handleErrorWith(report("record", _)) >>
                (if (isAligned(status))
                   offerStart.attempt.flatMap {
                     case Right(_)  => continueUntilReleased
                     case Left(err) => report("queue_offer", err) >> pauseThenRetry
                   }
                 else pauseThenRetry)
          }
      }
    }

    loop(1L)
  }

  private[consensus] def runRecoveryPlanBarrierLoop[F[_]: Temporal, A](
    inspect: F[A],
    isAligned: A => Boolean,
    record: (A, Long) => F[Unit],
    pause: F[Unit],
    offerStart: F[Unit],
    startPending: F[Boolean],
    reportFailure: (String, Throwable) => F[Unit]
  ): F[Unit] =
    runFirstRoundAlignmentLoop(inspect, isAligned, record, pause, offerStart, startPending, reportFailure)

  /** Classify only the exact named recovery peers. Unrelated responsive/Ready peers are intentionally ignored. */
  private[consensus] def recoveryPlanBarrierStatus(
    selfId: PeerId,
    committee: SortedSet[PeerId],
    selfReady: Boolean,
    responsivePeerStates: Map[PeerId, NodeState],
    peerOutcomes: Map[PeerId, RecoveryPlanPeerOutcome]
  ): RecoveryPlanBarrierStatus =
    firstRoundAlignmentBarrierStatus(
      selfId,
      committee,
      FirstRoundAlignmentRequirement.AllMembers,
      selfReady,
      responsivePeerStates,
      peerOutcomes
    )

  /** Classify only members of the expected committee and count exact outcomes according to `requirement`.
    *
    * Unrelated Ready peers are ignored. A quorum requirement may succeed while absent/mismatched minority members remain visible in the
    * diagnostic sets; an all-member recovery requirement succeeds only when every named process is exact by construction.
    */
  private[consensus] def firstRoundAlignmentBarrierStatus(
    selfId: PeerId,
    committee: SortedSet[PeerId],
    requirement: FirstRoundAlignmentRequirement,
    selfReady: Boolean,
    responsivePeerStates: Map[PeerId, NodeState],
    peerOutcomes: Map[PeerId, RecoveryPlanPeerOutcome]
  ): RecoveryPlanBarrierStatus = {
    val expectedExternal = committee - selfId
    val expectedPresent = expectedExternal.intersect(responsivePeerStates.keySet)
    val missingSession = expectedExternal -- responsivePeerStates.keySet
    val invalidState = SortedMap.from(expectedPresent.toList.flatMap { peerId =>
      responsivePeerStates.get(peerId).collect {
        case state if state =!= NodeState.Ready && state =!= NodeState.WaitingForReady => peerId -> state
      }
    })
    val fetchable = expectedPresent -- invalidState.keySet
    val alignedPeers = SortedSet.from(fetchable.filter(peerOutcomes.get(_).contains(RecoveryPlanPeerOutcome.Aligned)))
    val missingOutcome = SortedSet.from(fetchable.filter(peerOutcomes.get(_).contains(RecoveryPlanPeerOutcome.Missing)))
    val mismatchedOutcome = SortedSet.from(fetchable.filter(peerOutcomes.get(_).contains(RecoveryPlanPeerOutcome.Mismatched)))
    val explicitFetchFailures = fetchable.filter(peerOutcomes.get(_).contains(RecoveryPlanPeerOutcome.FetchFailed))
    val unobserved = fetchable -- peerOutcomes.keySet
    val fetchFailed = SortedSet.from(explicitFetchFailures ++ unobserved)

    RecoveryPlanBarrierStatus(
      requirement,
      committee.size,
      selfReady,
      invalidCommittee = !requirement.validFor(committee.size) || !committee.contains(selfId),
      expectedExternal,
      alignedPeers,
      missingSession,
      invalidState,
      missingOutcome,
      mismatchedOutcome,
      fetchFailed
    )
  }

  /** Classify stored first-round Facilities and bounded peer-ahead probes without granting either new authority.
    *
    * Only a matching Facility from the expected committee and current Ready/WaitingForReady session can become a pulse candidate. The
    * candidate's latest typed outcome must then still equal the installed parent; a candidate already ahead routes the held follower back
    * through normal recovery instead of opening a stale round. A committee member observed declaring beyond the first-round key may be
    * queried for that same ahead proof, but cannot become a release candidate without a matching Facility.
    */
  private[consensus] def normalFirstRoundPulseStatus(
    committee: SortedSet[PeerId],
    matchingFacilityOrigins: Set[PeerId],
    aheadProbeOrigins: Set[PeerId],
    responsivePeerStates: Map[PeerId, NodeState],
    peerOutcomes: Map[PeerId, NormalFirstRoundPulsePeerOutcome]
  ): NormalFirstRoundPulseStatus = {
    val expectedOrigins = SortedSet.from(matchingFacilityOrigins.intersect(committee))
    val probedOrigins = SortedSet.from((matchingFacilityOrigins ++ aheadProbeOrigins).intersect(committee))
    val present = probedOrigins.intersect(responsivePeerStates.keySet)
    val missingSession = probedOrigins -- responsivePeerStates.keySet
    val invalidState = SortedMap.from(present.toList.flatMap { peerId =>
      responsivePeerStates.get(peerId).collect {
        case state if state =!= NodeState.Ready && state =!= NodeState.WaitingForReady => peerId -> state
      }
    })
    val fetchable = present -- invalidState.keySet
    val aligned = SortedSet.from(
      fetchable.intersect(expectedOrigins).filter(peerOutcomes.get(_).contains(NormalFirstRoundPulsePeerOutcome.Aligned))
    )
    val ahead = SortedSet.from(fetchable.filter(peerOutcomes.get(_).contains(NormalFirstRoundPulsePeerOutcome.Ahead)))
    val missing = SortedSet.from(fetchable.filter(peerOutcomes.get(_).contains(NormalFirstRoundPulsePeerOutcome.Missing)))
    val mismatched = SortedSet.from(fetchable.filter(peerOutcomes.get(_).contains(NormalFirstRoundPulsePeerOutcome.Mismatched)))
    val explicitFailures = fetchable.filter(peerOutcomes.get(_).contains(NormalFirstRoundPulsePeerOutcome.FetchFailed))
    val unobserved = fetchable -- peerOutcomes.keySet

    NormalFirstRoundPulseStatus(
      expectedOrigins,
      aligned,
      ahead,
      missingSession,
      invalidState,
      missing,
      mismatched,
      SortedSet.from(explicitFailures ++ unobserved)
    )
  }

  /** Once any valid pulse origin proves it has advanced beyond the installed parent, that generation is recovery-only. Even if the
    * advancing origin later disappears and another peer still serves the parent, the stale first round must not be reopened while the
    * replacement download is in flight.
    */
  private[consensus] def shouldReleaseNormalFirstRoundPulse(
    status: NormalFirstRoundPulseStatus,
    recoveryAlreadyTriggered: Boolean
  ): Boolean =
    !recoveryAlreadyTriggered && status.releaseOrigin.nonEmpty

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
