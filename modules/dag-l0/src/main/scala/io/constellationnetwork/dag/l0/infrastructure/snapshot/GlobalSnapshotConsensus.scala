package io.constellationnetwork.dag.l0.infrastructure.snapshot

import java.security.KeyPair

import cats.Parallel
import cats.data.NonEmptySet
import cats.effect.kernel.{Async, Fiber, Ref}
import cats.effect.std.{Queue, Random, Supervisor}
import cats.syntax.all._

import scala.collection.immutable.SortedMap

import io.constellationnetwork.dag.l0.config.types.AppConfig
import io.constellationnetwork.dag.l0.domain.snapshot.programs.{
  GlobalSnapshotEventCutter,
  SnapshotBinaryFeeCalculator,
  UpdateNodeParametersCutter
}
import io.constellationnetwork.dag.l0.infrastructure.rewards.RewardsService
import io.constellationnetwork.dag.l0.infrastructure.snapshot.event._
import io.constellationnetwork.dag.l0.infrastructure.snapshot.schema.{GlobalConsensusKind, GlobalConsensusOutcome}
import io.constellationnetwork.domain.seedlist.SeedlistEntry
import io.constellationnetwork.json.{JsonBrotliBinarySerializer, JsonSerializer}
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.node.shared.cli.CliMethod
import io.constellationnetwork.node.shared.config.DefaultDelegatedRewardsConfigProvider
import io.constellationnetwork.node.shared.config.types.SharedConfig
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
import io.constellationnetwork.node.shared.infrastructure.snapshot.storage.PeerHistorySidecarStorage
import io.constellationnetwork.node.shared.logger.LoggerBundle
import io.constellationnetwork.node.shared.modules.{SharedServices, SharedValidators}
import io.constellationnetwork.node.shared.resources.ConsensusDispatcher
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Amount
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.gossip.RumorRaw
import io.constellationnetwork.schema.mpt.{GlobalStateKey, MptStore}
import io.constellationnetwork.schema.peer.{Peer, PeerId}
import io.constellationnetwork.schema.snapshot.SnapshotMetadata
import io.constellationnetwork.security._

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
    validators: SharedValidators[F],
    sharedServices: SharedServices[F, R],
    appConfig: AppConfig,
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

      // v20: env-resolved Core committee size threaded into `ConsensusConfig.coreCommitteeSize`
      // so it folds into `deterministicConfigHash`. Resolution happens here (single point) and
      // the resulting `effectiveConsensusConfig` is what every downstream component reads --
      // ConsensusStorage, consensusFunctions, stateAdvancer, stateCreator, ConsensusEventLoop.
      // The default `3` mirrors the dev-environment value used by
      // `GlobalSnapshotConsensusStateCreator.make` and `CurrencySnapshotConsensus`.
      resolvedCoreCommitteeSize = appConfig.snapshot.coreCommitteeSize.get(appConfig.environment).map(_.value).getOrElse(3)
      // v33: env-resolved quorum-denominator-shrink activation threshold (absent env entry = 0 =
      // rung disabled). Folded into `deterministicConfigHash` via the consensus config copy below.
      resolvedQuorumShrinkActivationViews = appConfig.snapshot.quorumShrinkActivationViews
        .get(appConfig.environment)
        .getOrElse(0)
      // Bounded probation re-entry lane: env-resolved minimum probation slots (absent env entry =
      // 0 = lane inert). Folded into `deterministicConfigHash` via the consensus config copy below.
      resolvedActiveAdmissionMinProbationReentrySlots = appConfig.snapshot.activeAdmissionMinProbationReentrySlots
        .get(appConfig.environment)
        .getOrElse(0)
      // Recent-signer pool lookback depth: env-resolved, floored to the DemotionConsecutiveMisses
      // constant (3) so a low operator value cannot disable the recent-signer path (Codex review #2).
      // Absent env entry resolves to that floor (the pre-change lookback). Folded into the copy below.
      resolvedActiveAdmissionRecentSignerWindow = math.max(
        3,
        appConfig.snapshot.activeAdmissionRecentSignerWindow.get(appConfig.environment).getOrElse(3)
      )
      // Active-set growth target + hard cap: env-resolved (the coreCommitteeSize pattern) and
      // folded into `deterministicConfigHash` via the copy below. Absent env entries preserve the
      // ConsensusConfig scalar resolution (None -> coreCommitteeSize fallback at the consumers).
      // INVARIANTS (conf convention): target > coreFloor and max >= coreFloor for every
      // environment -- violating either closes the admission feeder below the Core floor
      // (v4.1.0 base scalars: target 7 / max 13 vs integrationnet floor 9 and mainnet floor 15).
      resolvedActiveFacilitatorTarget = appConfig.snapshot.activeFacilitatorTarget
        .get(appConfig.environment)
        .orElse(appConfig.snapshot.consensus.activeFacilitatorTarget)
      resolvedActiveFacilitatorMax = appConfig.snapshot.activeFacilitatorMax
        .get(appConfig.environment)
        .orElse(appConfig.snapshot.consensus.activeFacilitatorMax)
      effectiveConsensusConfig = appConfig.snapshot.consensus.copy(
        coreCommitteeSize = Some(resolvedCoreCommitteeSize),
        quorumShrinkActivationViews = resolvedQuorumShrinkActivationViews,
        activeAdmissionMinProbationReentrySlots = resolvedActiveAdmissionMinProbationReentrySlots,
        activeAdmissionRecentSignerWindow = resolvedActiveAdmissionRecentSignerWindow,
        activeFacilitatorTarget = resolvedActiveFacilitatorTarget,
        activeFacilitatorMax = resolvedActiveFacilitatorMax
      )
      // Fail fast on Core-controller sizing invariants. Enforced only for explicitly configured
      // values; these bounds classify Core and do not cap broad Tier-1 signing membership.
      _ <- new IllegalArgumentException(
        s"active-facilitator-target ($resolvedActiveFacilitatorTarget) must exceed core-committee-size" +
          s" ($resolvedCoreCommitteeSize): the Core controller requires classification headroom"
      ).raiseError[F, Unit]
        .whenA(resolvedActiveFacilitatorTarget.exists(_ <= resolvedCoreCommitteeSize))
      _ <- new IllegalArgumentException(
        s"active-facilitator-max ($resolvedActiveFacilitatorMax) must be >= core-committee-size" +
          s" ($resolvedCoreCommitteeSize): the Core classification cannot cap below its floor"
      ).raiseError[F, Unit]
        .whenA(resolvedActiveFacilitatorMax.exists(_ < resolvedCoreCommitteeSize))
      _ <- new IllegalArgumentException(
        s"active-facilitator-target ($resolvedActiveFacilitatorTarget) must not exceed" +
          s" active-facilitator-max ($resolvedActiveFacilitatorMax)"
      ).raiseError[F, Unit]
        .whenA((resolvedActiveFacilitatorTarget, resolvedActiveFacilitatorMax).tupled.exists { case (t, m) => t > m })

      consensusStorage <- ConsensusStorage.make[
        F,
        GlobalSnapshotEvent,
        GlobalSnapshotKey,
        GlobalSnapshotArtifact,
        GlobalSnapshotContext,
        GlobalSnapshotStatus,
        GlobalConsensusOutcome,
        GlobalConsensusKind
      ](effectiveConsensusConfig)

      // Global L0 injects the command queue so the round-creation Tier-1 finality audit can
      // enqueue the existing certificate-assembly command before sending its first Facility.
      // Currency L0 and generic callers continue to let ConsensusEventLoop allocate internally.
      consensusQueue <- Queue.unbounded[
        F,
        ConsensusCommand[GlobalSnapshotKey, GlobalSnapshotArtifact, GlobalSnapshotContext, GlobalConsensusOutcome]
      ]
      // Node-local proof-miss streaks are intentionally not restored. A restart starts at zero,
      // delaying Tier-1 eviction until three new consecutive misses are locally observed.
      tier1FinalityMissHistoryRef <- Ref.of[F, FinalityParticipationAuditor.MissHistory](
        FinalityParticipationAuditor.MissHistory.empty
      )

      evictionVoter = new GossipingEvictionVoter[
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
        appConfig.snapshot.maxFacilitatorCount.get(appConfig.environment).map(_.value)
      )

      // Alpha.94: node-local sidecar for the post-finalization `ConsensusOperationalState`.
      // Closes the one-round-stale `snapshot.peerHistory` gap surfaced in `project_alpha92_wedge_may21.md`.
      // Co-located under the snapshot path so retention sweeps can clean both with the same ordinal
      // discriminator. Best-effort writes; rollback reads fall back to `snapshot.peerHistory` when
      // the sidecar is absent or malformed.
      peerHistorySidecar <- PeerHistorySidecarStorage.make[F](appConfig.snapshot.snapshotPath / "peerHistory")

      stateAdvancer =
        GlobalSnapshotConsensusStateAdvancer.make(
          effectiveConsensusConfig,
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
          peerHistorySidecar
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
          evictionVoter,
          consensusQueue,
          tier1FinalityMissHistoryRef
        )

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

      directPushFn = ConsensusDirectSender.makeDirectPushFn(clusterStorage, consensusClient)
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
          viewChangeVoter,
          timeoutVoter,
          evictionVoter,
          admissionVoter,
          (o: GlobalConsensusOutcome) => !o.recentProofSizes.values.exists(_ >= effectiveConsensusConfig.bootstrapCompleteProofsThreshold),
          (o: GlobalConsensusOutcome) => o.readmissionCountdown.filter(_._2 > 0).keySet,
          (o: GlobalConsensusOutcome) => o.finished.candidates.value,
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
          peersCommittedAheadProbe,
          injectedHealthRef,
          Some(consensusQueue)
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
      ](consensusStorage, rumorQueue)

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
