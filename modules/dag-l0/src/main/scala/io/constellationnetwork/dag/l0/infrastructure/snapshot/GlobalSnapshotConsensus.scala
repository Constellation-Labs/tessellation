package io.constellationnetwork.dag.l0.infrastructure.snapshot

import java.security.KeyPair

import cats.Parallel
import cats.data.NonEmptySet
import cats.effect.kernel.{Async, Fiber, Ref}
import cats.effect.std.{Queue, Random, Supervisor}
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.dag.l0._
import io.constellationnetwork.dag.l0.config.types.AppConfig
import io.constellationnetwork.dag.l0.domain.snapshot.programs.{
  GlobalSnapshotEventCutter,
  SnapshotBinaryFeeCalculator,
  UpdateNodeParametersCutter
}
import io.constellationnetwork.dag.l0.domain.snapshot.recovery.{Gl0RecoveryPlanLoader, Gl0RecoveryPlanReceipt}
import io.constellationnetwork.dag.l0.domain.snapshot.storages.SnapshotDownloadStorage
import io.constellationnetwork.dag.l0.infrastructure.rewards.RewardsService
import io.constellationnetwork.dag.l0.infrastructure.snapshot.event._
import io.constellationnetwork.dag.l0.infrastructure.snapshot.schema.{GlobalConsensusKind, GlobalConsensusOutcome}
import io.constellationnetwork.domain.seedlist.SeedlistEntry
import io.constellationnetwork.json.{JsonBrotliBinarySerializer, JsonSerializer}
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.node.shared.cli.CliMethod
import io.constellationnetwork.node.shared.config.DefaultDelegatedRewardsConfigProvider
import io.constellationnetwork.node.shared.config.types.{ConsensusConfig, SharedConfig}
import io.constellationnetwork.node.shared.domain.cluster.services.Session
import io.constellationnetwork.node.shared.domain.cluster.storage.ClusterStorage
import io.constellationnetwork.node.shared.domain.consensus.ConsensusFunctions
import io.constellationnetwork.node.shared.domain.gossip.Gossip
import io.constellationnetwork.node.shared.domain.node.NodeStorage
import io.constellationnetwork.node.shared.domain.rewards.Rewards
import io.constellationnetwork.node.shared.domain.snapshot.storage.{LastNGlobalSnapshotStorage, LastSnapshotStorage, SnapshotStorage}
import io.constellationnetwork.node.shared.domain.statechannel.{FeeCalculator, FeeCalculatorConfig}
import io.constellationnetwork.node.shared.domain.swap.block.AllowSpendBlockAcceptanceManager
import io.constellationnetwork.node.shared.domain.tokenlock.block.TokenLockBlockAcceptanceManager
import io.constellationnetwork.node.shared.http.p2p.PeerResponse
import io.constellationnetwork.node.shared.infrastructure.block.processing.BlockAcceptanceManager
import io.constellationnetwork.node.shared.infrastructure.consensus._
import io.constellationnetwork.node.shared.infrastructure.consensus.engine.{ConsensusCommand, ConsensusEventLoop, _}
import io.constellationnetwork.node.shared.infrastructure.consensus.message.ConsensusPeerDeclaration
import io.constellationnetwork.node.shared.infrastructure.consensus.state._
import io.constellationnetwork.node.shared.infrastructure.gossip.RumorHandler
import io.constellationnetwork.node.shared.infrastructure.gossip.event.{ChainTip, EventGossipClient}
import io.constellationnetwork.node.shared.infrastructure.mempool.EventMempool
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.node.shared.infrastructure.node.RestartService
import io.constellationnetwork.node.shared.infrastructure.snapshot._
import io.constellationnetwork.node.shared.infrastructure.snapshot.managers.global.{
  GlobalSnapshotAcceptanceManager,
  GlobalSnapshotStateChannelAcceptanceManager,
  GlobalSnapshotStateChannelEventsProcessor
}
import io.constellationnetwork.node.shared.infrastructure.snapshot.storage.{OrdinalJsonSidecarStorage, PeerHistorySidecarStorage}
import io.constellationnetwork.node.shared.logger.LoggerBundle
import io.constellationnetwork.node.shared.modules.{SharedServices, SharedValidators}
import io.constellationnetwork.node.shared.resources.ConsensusDispatcher
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.{Amount, Balance}
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.gossip.RumorRaw
import io.constellationnetwork.schema.mpt.{GlobalStateKey, MptStore}
import io.constellationnetwork.schema.peer.{Peer, PeerId}
import io.constellationnetwork.schema.snapshot.SnapshotMetadata
import io.constellationnetwork.security._
import io.constellationnetwork.security.key.ops._

import eu.timepit.refined.types.numeric.NonNegLong
import io.circe.Json
import org.http4s.client.Client
import org.typelevel.log4cats.slf4j.Slf4jLogger

/** Factory for creating the Global L0 consensus engine.
  *
  * Wires together all components and starts the consensus background stream. Returns a Consensus instance with handler (for gossip),
  * manager (external API), storage (state queries), and routes (HTTP endpoints).
  *
  * @see
  *   ConsensusEventLoop for FSM and command processing
  */
object GlobalSnapshotConsensus {

  def make[F[_]: Async: Parallel: Random: JsonSerializer: HasherSelector: SecurityProvider: Metrics, R <: CliMethod](
    sharedCfg: SharedConfig,
    gossip: Gossip[F],
    selfId: PeerId,
    keyPair: KeyPair,
    seedlist: Option[Set[SeedlistEntry]],
    collateral: Amount,
    clusterStorage: ClusterStorage[F],
    nodeStorage: NodeStorage[F],
    globalSnapshotStorage: SnapshotStorage[F, GlobalSnapshotArtifact, GlobalSnapshotContext],
    snapshotDownloadStorage: SnapshotDownloadStorage[F],
    validators: SharedValidators[F],
    sharedServices: SharedServices[F, R],
    appConfig: AppConfig,
    effectiveConsensusConfig: ConsensusConfig,
    stateChannelPullDelay: NonNegLong,
    stateChannelPurgeDelay: NonNegLong,
    stateChannelAllowanceLists: Option[Map[Address, NonEmptySet[PeerId]]],
    feeConfigs: SortedMap[SnapshotOrdinal, FeeCalculatorConfig],
    client: Client[F],
    session: Session[F],
    rewardsService: RewardsService[F],
    txHasher: Hasher[F],
    restartService: RestartService[F, R],
    lastNGlobalSnapshotStorage: LastNGlobalSnapshotStorage[F],
    lastGlobalSnapshotStorage: LastSnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo],
    getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]],
    mptStore: MptStore[F, GlobalStateKey],
    eventMempool: EventMempool[F, GlobalSnapshotEvent, GlobalStateKey],
    eventGossipClient: EventGossipClient[F, GlobalSnapshotEvent],
    loggerBundle: LoggerBundle[F],
    rumorQueue: Queue[F, Hashed[RumorRaw]],
    // B2 witness channel: probation-peer chain tips from the mesh gossip layer. See
    // StallDetector.maybeEmitAdmissionVotes for usage. Injected as a thunk because
    // EventGossipDaemon is constructed after consensus in Main.scala — the Ref pattern in
    // Main.scala populates the real getter once the daemon is up; before that it returns
    // Map.empty and no admission votes fire (safe default).
    getPeerChainTips: F[Map[PeerId, ChainTip]],
    configuredRecoveryPlan: F[Option[Gl0RecoveryPlanLoader.Verified]],
    recoveryPlanReceipt: Gl0RecoveryPlanReceipt[F],
    initiallyHoldConsensusFirstRound: Boolean,
    // Shared consensus-health Ref from SharedServices. When provided, the engine's
    // AbandonmentTracker writes wedge signals into the same Ref that Cluster.leave()'s guard
    // reads, activating the leave-refusal behavior. When None, the engine creates its own
    // internal Ref and the cluster-level guard sees only the empty default (inert).
    injectedHealthRef: Option[Ref[F, ConsensusHealthStatus]] = None,
    // Dedicated dispatcher (EC + EC-scoped supervisor) for the FSM consume fiber. When provided,
    // the whole `loop.run.compile.drain` is shifted onto its EC via `Async[F].evalOn` and the fiber
    // is supervised by the dispatcher's supervisor (finalized before the EC, so the fiber is
    // cancelled while the pool is still alive). When None (default, and what tests use) the loop
    // runs on the ambient runtime under the outer supervisor -- same behaviour as before PR-2's
    // executor isolation. See `ConsensusExecutor`.
    consensusDispatcher: Option[ConsensusDispatcher[F]] = None
  )(implicit supervisor: Supervisor[F], globalStateProofSelector: GlobalStateProofSelector): F[GlobalSnapshotConsensus[F]] =
    for {
      globalStateChannelManager <- GlobalSnapshotStateChannelAcceptanceManager
        .make[F](stateChannelAllowanceLists, pullDelay = stateChannelPullDelay, purgeDelay = stateChannelPurgeDelay)

      feeCalculator = FeeCalculator.make(feeConfigs)

      snapshotAcceptanceManager =
        GlobalSnapshotAcceptanceManager.make(
          sharedCfg.fieldsAddedOrdinals,
          sharedCfg.metagraphsSync,
          sharedCfg.environment,
          BlockAcceptanceManager.make[F](validators.blockValidator, txHasher),
          AllowSpendBlockAcceptanceManager.make[F](validators.allowSpendBlockValidator),
          TokenLockBlockAcceptanceManager.make[F](validators.tokenLockBlockValidator),
          GlobalSnapshotStateChannelEventsProcessor.make[F](
            validators.stateChannelValidator,
            globalStateChannelManager,
            sharedServices.currencySnapshotContextFns,
            feeCalculator,
            mptStore,
            sharedCfg.fieldsAddedOrdinals,
            sharedCfg.environment
          ),
          sharedServices.updateNodeParametersAcceptanceManager,
          sharedServices.updateDelegatedStakeAcceptanceManager,
          sharedServices.updateNodeCollateralAcceptanceManager,
          validators.spendActionValidator,
          validators.pricingUpdateValidator,
          sharedServices.priceStateUpdater,
          collateral,
          sharedCfg.delegatedStaking.withdrawalTimeLimit
            .getOrElse(sharedCfg.environment, EpochProgress.MinValue),
          mptStore,
          loggerBundle
        )

      // Startup resolves this exact object once for both the join fence and live engine. Every
      // downstream component reads the same projection; the fallback is retained defensively.
      resolvedCoreCommitteeSize = effectiveConsensusConfig.coreCommitteeSize.getOrElse(3)
      resolvedCertifiedConsensusActivationKey = effectiveConsensusConfig.certifiedConsensusActivationKey

      certifiedVoteLockPersistence <- CertifiedVoteLockPersistence.forSnapshotOrdinal[F](
        appConfig.snapshot.snapshotPath / "certifiedVoteLocks"
      )
      consensusStorage <- ConsensusStorage.make[
        F,
        GlobalSnapshotEvent,
        GlobalSnapshotKey,
        GlobalSnapshotArtifact,
        GlobalSnapshotContext,
        GlobalSnapshotStatus,
        GlobalConsensusOutcome,
        GlobalConsensusKind
      ](effectiveConsensusConfig, LegacyViewChangePolicy.FreezeAfterVote, certifiedVoteLockPersistence)

      // Global L0 injects the command queue so the round-creation signing-participation audit can
      // enqueue the existing certificate-assembly command before sending its first Facility.
      // Currency L0 and generic callers continue to let ConsensusEventLoop allocate internally.
      consensusQueue <- Queue.unbounded[
        F,
        ConsensusCommand[GlobalSnapshotKey, GlobalSnapshotArtifact, GlobalSnapshotContext, GlobalConsensusOutcome]
      ]
      // Node-local proof-miss streaks are intentionally not restored. A restart starts at zero,
      // delaying certified signing-seat replacement until three new consecutive misses are locally observed.
      finalityParticipationMissHistoryRef <- Ref.of[F, FinalityParticipationAuditor.MissHistory](
        FinalityParticipationAuditor.MissHistory.empty
      )

      rawEvictionVoter = new GossipingEvictionVoter[
        F,
        GlobalSnapshotEvent,
        GlobalSnapshotKey,
        GlobalSnapshotArtifact,
        GlobalSnapshotContext,
        GlobalSnapshotStatus,
        GlobalConsensusOutcome,
        GlobalConsensusKind
      ](
        selfId,
        keyPair,
        gossip,
        consensusStorage,
        (o: GlobalConsensusOutcome) => o.finished.snapshotHash,
        HealthDerivedMembershipPolicy.RetainSigningLeases,
        Slf4jLogger.getLogger[F]
      )
      consensusFunctions =
        GlobalSnapshotConsensusFunctions.make[F](
          snapshotAcceptanceManager,
          collateral,
          rewardsService,
          GlobalSnapshotEventCutter.make(
            effectiveConsensusConfig.eventCutter.maxBinarySizeBytes,
            SnapshotBinaryFeeCalculator.make(appConfig.shared.feeConfigs, mptStore)
          ),
          UpdateNodeParametersCutter.make(effectiveConsensusConfig.eventCutter.maxUpdateNodeParametersSize),
          appConfig.environment,
          DefaultDelegatedRewardsConfigProvider,
          sharedCfg.fieldsAddedOrdinals.tessellation3Migration
            .getOrElse(sharedCfg.environment, SnapshotOrdinal.MinValue),
          sharedCfg.fieldsAddedOrdinals.setSumFix
            .getOrElse(sharedCfg.environment, SnapshotOrdinal.MinValue),
          sharedCfg.fieldsAddedOrdinals.delegatedRewardsFullCommittee
            .getOrElse(sharedCfg.environment, SnapshotOrdinal.MaxValue),
          sharedCfg.incrementalDelegatedStakingStartingOrdinal
            .getOrElse(sharedCfg.environment, SnapshotOrdinal.MinValue),
          mptStore,
          effectiveConsensusConfig.activeAdmissionPromoteThreshold
        )

      facilitatorSelector = FacilitatorSelector.make(
        effectiveConsensusConfig.facilitatorSelectionMax
      )

      // Alpha.94: node-local sidecar for the post-finalization `ConsensusOperationalState`.
      // Closes the one-round-stale `snapshot.peerHistory` gap surfaced in `project_alpha92_wedge_may21.md`.
      // Co-located under the snapshot path so retention sweeps can clean both with the same ordinal
      // discriminator. Best-effort writes; rollback reads fall back to `snapshot.peerHistory` when
      // the sidecar is absent or malformed.
      peerHistorySidecar <- PeerHistorySidecarStorage.make[F](appConfig.snapshot.snapshotPath / "peerHistory")
      certifiedOutcomeSidecar <- OrdinalJsonSidecarStorage.make[F, GlobalConsensusOutcome](
        appConfig.snapshot.snapshotPath / "certifiedOutcomes"
      )
      certifiedOutcomeCutoff = appConfig.incremental.lastFullGlobalSnapshotOrdinal
        .getOrElse(appConfig.environment, SnapshotOrdinal.MinValue)
      recoveryPlanPreflight = (outcome: GlobalConsensusOutcome) =>
        configuredRecoveryPlan.flatMap(_.traverse_ { verified =>
          val plan = verified.plan
          for {
            hashedSnapshot <- HasherSelector[F].forOrdinal(outcome.key) { implicit hasher =>
              outcome.finished.signedMajorityArtifact.toHashed[F]
            }
            expected = GlobalRecoveryPlanOutcome.seed(
              outcome.finished.signedMajorityArtifact,
              outcome.finished.context,
              hashedSnapshot.hash,
              plan.committee
            )
            _ <- new IllegalStateException(
              s"Downloaded GL0 recovery outcome does not match signed plan=${plan.planId.value}: " +
                s"expectedAnchor=${plan.anchor.ordinal.value.value}/${plan.anchor.snapshotHash.value} " +
                s"got=${outcome.key.value.value}/${hashedSnapshot.hash.value}"
            ).raiseError[F, Unit]
              .unlessA(
                outcome.key === plan.anchor.ordinal &&
                  hashedSnapshot.hash === plan.anchor.snapshotHash &&
                  outcome === expected
              )
            ineligible <- plan.committee.toList.filterA { peerId =>
              peerId.toPublic[F].map(_.toAddress).map { address =>
                !outcome.finished.context.balances.get(address).getOrElse(Balance.empty).satisfiesCollateral(collateral)
              }
            }
            _ <- new IllegalStateException(
              s"Downloaded GL0 recovery outcome has uncollateralized planned members=${ineligible.map(_.value.value).mkString(",")}"
            ).raiseError[F, Unit].whenA(ineligible.nonEmpty)
            _ <- recoveryPlanReceipt.consume(verified.signed)
          } yield ()
        })

      stateAdvancer =
        GlobalSnapshotConsensusStateAdvancer.make(
          effectiveConsensusConfig,
          appConfig.environment.entryName,
          keyPair,
          consensusStorage,
          globalSnapshotStorage,
          consensusFunctions,
          gossip,
          restartService,
          nodeStorage,
          appConfig.shared.leavingDelay,
          lastNGlobalSnapshotStorage,
          lastGlobalSnapshotStorage,
          getGlobalSnapshotByOrdinal,
          clusterStorage,
          eventMempool,
          eventGossipClient,
          loggerBundle,
          mptStore,
          facilitatorSelector,
          seedlist.fold(Set.empty[PeerId])(_.iterator.map(_.peerId).toSet),
          HealthDerivedMembershipPolicy.RetainSigningLeases,
          (key: GlobalSnapshotKey) => consensusQueue.offer(ConsensusCommand.RestartAfterSoftReset(key))
        )

      peerQualityTracker <- PeerQualityTracker.make[F]

      tcaFilter = TrailingCommonAncestorFilter.make[F]

      stateCreator =
        GlobalSnapshotConsensusStateCreator.make(
          consensusFunctions,
          consensusStorage,
          gossip,
          selfId,
          seedlist,
          facilitatorSelector,
          effectiveConsensusConfig.deterministicConfigHash,
          effectiveConsensusConfig,
          peerQualityTracker,
          tcaFilter,
          eventMempool,
          sharedServices.localHealthMonitor,
          // v19 per-environment Core floor. v20 routes the env-resolved value through
          // `effectiveConsensusConfig.coreCommitteeSize`, so this single binding is what
          // both the state creator and `deterministicConfigHash` see.
          resolvedCoreCommitteeSize,
          rawEvictionVoter,
          consensusQueue,
          finalityParticipationMissHistoryRef,
          HealthDerivedMembershipPolicy.RetainSigningLeases
        )

      certifiedDownloadPreflight = GlobalCertifiedDownloadValidator.make[F](
        effectiveConsensusConfig,
        resolvedCoreCommitteeSize,
        seedlist.fold(Set.empty[PeerId])(_.iterator.map(_.peerId).toSet),
        facilitatorSelector,
        consensusFunctions,
        snapshotDownloadStorage,
        certifiedOutcomeSidecar,
        stateAdvancer
      )

      outcomePreInitialize = (outcome: GlobalConsensusOutcome) =>
        configuredRecoveryPlan.flatMap {
          case Some(verified) if outcome.key === verified.plan.anchor.ordinal => recoveryPlanPreflight(outcome)
          case _                                                              => certifiedDownloadPreflight(outcome) >> recoveryPlanPreflight(outcome)
        }

      stateRemover =
        GlobalSnapshotConsensusStateRemover.make(
          consensusStorage,
          gossip
        )

      consensusOps = GlobalSnapshotConsensusOps.make

      stateUpdater =
        ConsensusStateUpdater.make(
          stateAdvancer,
          consensusStorage,
          consensusOps
        )

      consensusClient = ConsensusClient.make[F, GlobalSnapshotKey, GlobalConsensusOutcome](client, session)

      directPushFn <- ConsensusDirectSender.makeDirectPushFn(clusterStorage, consensusClient)
      _ <- gossip.setDirectPushFn(directPushFn)

      viewChangeVoter = new GossipingViewChangeVoter[
        F,
        GlobalSnapshotEvent,
        GlobalSnapshotKey,
        GlobalSnapshotArtifact,
        GlobalSnapshotContext,
        GlobalSnapshotStatus,
        GlobalConsensusOutcome,
        GlobalConsensusKind
      ](
        selfId,
        keyPair,
        gossip,
        consensusStorage,
        (o: GlobalConsensusOutcome) => o.finished.snapshotHash,
        effectiveConsensusConfig.quorumThresholdFraction,
        Slf4jLogger.getLogger[F]
      )

      timeoutVoter = new GossipingTimeoutVoter[
        F,
        GlobalSnapshotEvent,
        GlobalSnapshotKey,
        GlobalSnapshotArtifact,
        GlobalSnapshotContext,
        GlobalSnapshotStatus,
        GlobalConsensusOutcome,
        GlobalConsensusKind
      ](
        selfId,
        keyPair,
        gossip,
        consensusStorage,
        (o: GlobalConsensusOutcome) => o.finished.snapshotHash,
        effectiveConsensusConfig.quorumThresholdFraction,
        Slf4jLogger.getLogger[F]
      )

      admissionVoter = new GossipingAdmissionVoter[
        F,
        GlobalSnapshotEvent,
        GlobalSnapshotKey,
        GlobalSnapshotArtifact,
        GlobalSnapshotContext,
        GlobalSnapshotStatus,
        GlobalConsensusOutcome,
        GlobalConsensusKind
      ](
        selfId,
        keyPair,
        gossip,
        consensusStorage,
        (o: GlobalConsensusOutcome) => o.finished.snapshotHash,
        HealthDerivedMembershipPolicy.RetainSigningLeases,
        Slf4jLogger.getLogger[F]
      )

      // HTTP preflight for the rumor-stale abandonment escalation (issue #1533): ask a bounded
      // random sample of Ready peers for their latest global snapshot metadata. At least two peers
      // and a strict majority of responders must agree on `(ordinal, hash)`, confirming recovery
      // has something to fetch without trusting a single response.
      fetchLatestCommittedMetadata = {
        import org.http4s.circe.CirceEntityCodec.circeEntityDecoder
        val request = PeerResponse[F, SnapshotMetadata]("global-snapshots/latest/metadata")(client, session.some)
        (peer: Peer) => request(peer)
      }
      peersCommittedAheadProbe = PeersCommittedAheadProbe.make[F](clusterStorage, fetchLatestCommittedMetadata)
      fetchProbationChainTip = (peer: Peer) => eventGossipClient.getChainTip.run(Peer.toP2PContext(peer))
      admissionCandidateTipProbe = AdmissionCandidateTipProbe.make[F](
        clusterStorage,
        fetchLatestCommittedMetadata,
        fetchProbationChainTip
      )

      loop <-
        ConsensusEventLoop.build[
          F,
          GlobalSnapshotEvent,
          GlobalSnapshotKey,
          GlobalSnapshotArtifact,
          GlobalSnapshotContext,
          GlobalSnapshotStatus,
          GlobalConsensusOutcome,
          GlobalConsensusKind
        ](
          selfId,
          gossip,
          consensusStorage,
          stateCreator,
          stateUpdater,
          stateAdvancer,
          stateRemover,
          consensusOps,
          nodeStorage,
          clusterStorage,
          consensusFunctions,
          consensusClient,
          effectiveConsensusConfig,
          facilitatorSelector,
          peerQualityTracker,
          HealthDerivedMembershipPolicy.RetainSigningLeases,
          viewChangeVoter,
          timeoutVoter,
          rawEvictionVoter,
          admissionVoter,
          (o: GlobalConsensusOutcome) => !o.recentProofSizes.values.exists(_ >= effectiveConsensusConfig.bootstrapCompleteProofsThreshold),
          (o: GlobalConsensusOutcome) => ReadmissionMaintenance.probationPeers(o.readmissionCountdown),
          (o: GlobalConsensusOutcome) => o.finished.candidates.value,
          (o: GlobalConsensusOutcome) => o.controllerEvidence.flatMap(_.get(o.key)).fold(Set.empty[PeerId])(_.roundStartFacilitators.toSet),
          (key: GlobalSnapshotKey) =>
            ActiveFacilitatorAdmission.expansionAllowedAtOrdinal(
              key.value.value,
              effectiveConsensusConfig.activeAdmissionExpansionIntervalRounds
            ),
          (o: GlobalConsensusOutcome) => Some(o.finished.signedMajorityArtifact.proofs.toList.map(_.id.toPeerId).toSet),
          (o: GlobalConsensusOutcome) => o.finished.snapshotHash,
          (o: GlobalConsensusOutcome) => o.peerQuality.toMap,
          (o: GlobalConsensusOutcome) => o.recentRoundEndTimes.lastOption.map(_._2),
          getPeerChainTips,
          admissionCandidateTipProbe.some,
          peersCommittedAheadProbe,
          injectedHealthRef,
          Some(consensusQueue),
          Some((outcome: GlobalConsensusOutcome) =>
            outcome.finished.certifiedOutcome.traverse_(_ =>
              configuredRecoveryPlan.flatMap { recoveryPlan =>
                val pinned = recoveryPlan.fold(Set.empty[SnapshotOrdinal])(verified => Set(verified.plan.anchor.ordinal))
                certifiedOutcomeSidecar.write(outcome.key, outcome) >>
                  certifiedOutcomeSidecar.retain(certifiedOutcomeCutoff, outcome.key, pinned) >>
                  consensusStorage.deleteCertifiedVoteLock(outcome.key)
              }
            ) >>
              peerHistorySidecar.write(outcome.key, outcome.toOperationalState)
          ),
          Some((outcome: GlobalConsensusOutcome) =>
            certifiedOutcomeSidecar.deleteAbove(outcome.key) >>
              consensusStorage.deleteCertifiedVoteLocksAtOrBelow(outcome.key)
          ),
          onOutcomePreInitialize = Some(outcomePreInitialize),
          onOutcomeSafetyInitialized = Some((outcome: GlobalConsensusOutcome) =>
            configuredRecoveryPlan.flatMap {
              case Some(verified) if outcome.key === verified.plan.anchor.ordinal =>
                // The signed-plan synthetic anchor is the all-member barrier's exact value. Keep
                // serving it even after an early quorum advances, so an asymmetrically late member
                // can still fetch N and release its locally-held first-round gate.
                certifiedOutcomeSidecar.write(outcome.key, outcome)
              case _ =>
                outcome.finished.certifiedOutcome.fold(certifiedOutcomeSidecar.delete(outcome.key))(_ =>
                  certifiedOutcomeSidecar.write(outcome.key, outcome)
                )
            }
          ),
          onOutcomeRollbackInitialized = Some((outcome: GlobalConsensusOutcome, policy: ConsensusCommand.RollbackStartPolicy) =>
            policy match {
              case ConsensusCommand.RollbackStartPolicy.RequireAlignedCommittee(_) =>
                certifiedOutcomeSidecar.write(outcome.key, outcome) >>
                  certifiedOutcomeSidecar.deleteAbove(outcome.key) >>
                  consensusStorage.deleteCertifiedVoteLocksAbove(outcome.key)
              case _ => Async[F].unit
            }
          ),
          initiallyHoldFirstRound = initiallyHoldConsensusFirstRound,
          plannedRecoveryCommittee = Some(configuredRecoveryPlan.map(_.map(_.plan.committee)))
        )

      handler = GlobalConsensusHandler.make(loop.queue)

      routes = new ConsensusRoutes[
        F,
        GlobalSnapshotKey,
        GlobalSnapshotArtifact,
        GlobalSnapshotContext,
        GlobalSnapshotStatus,
        GlobalConsensusOutcome,
        GlobalConsensusKind
      ](consensusStorage, rumorQueue, Some(certifiedOutcomeSidecar.read))

      triggerEvent = loop.queue.offer(ConsensusCommand.FacilitateByEvent)

      // Pin the consume fiber onto the dedicated consensus EC when one was provided, supervised by
      // that dispatcher's EC-scoped supervisor so it is cancelled before the pool is shut down.
      // The shift covers the entire stream's compile-drain so queue.take blocks on the consensus
      // pool rather than the global runtime; downstream effects that elect to shift back via
      // `evalOn` (gossip emits, P2P calls) are not forced onto the consensus pool. Without a
      // dispatcher: run on the ambient runtime under the outer supervisor (test/default behaviour).
      _ <- consensusDispatcher.fold(supervisor.supervise(loop.run.compile.drain)) { d =>
        d.supervisor.supervise(Async[F].evalOn(loop.run.compile.drain, d.ec))
      }
      consensus = new Consensus(
        handler,
        consensusStorage,
        loop.manager,
        routes,
        consensusFunctions,
        Some(loop.healthRef),
        Some(triggerEvent)
      )
    } yield consensus
}
