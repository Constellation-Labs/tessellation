package io.constellationnetwork.currency.l0.snapshot

import java.security.KeyPair

import cats.Parallel
import cats.effect.kernel.Async
import cats.effect.std.{Queue, Random, Supervisor}
import cats.syntax.all._

import scala.collection.immutable.SortedSet
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration

import io.constellationnetwork.currency.dataApplication.{BaseDataApplicationL0Service, DataTransaction}
import io.constellationnetwork.currency.l0.snapshot.schema._
import io.constellationnetwork.currency.l0.snapshot.services.StateChannelSnapshotService
import io.constellationnetwork.currency.schema.CurrencyStateKey
import io.constellationnetwork.currency.schema.currency._
import io.constellationnetwork.domain.seedlist.SeedlistEntry
import io.constellationnetwork.env.AppEnvironment
import io.constellationnetwork.node.shared.config.types.{ConsensusConfig, SnapshotConfig}
import io.constellationnetwork.node.shared.domain.cluster.services.Session
import io.constellationnetwork.node.shared.domain.cluster.storage.ClusterStorage
import io.constellationnetwork.node.shared.domain.gossip.Gossip
import io.constellationnetwork.node.shared.domain.node.NodeStorage
import io.constellationnetwork.node.shared.domain.rewards.Rewards
import io.constellationnetwork.node.shared.domain.snapshot.storage.LastSyncGlobalSnapshotStorage
import io.constellationnetwork.node.shared.infrastructure.consensus._
import io.constellationnetwork.node.shared.infrastructure.consensus.engine._
import io.constellationnetwork.node.shared.infrastructure.consensus.message.ConsensusPeerDeclaration
import io.constellationnetwork.node.shared.infrastructure.consensus.state._
import io.constellationnetwork.node.shared.infrastructure.gossip.event.{ChainTip, EventGossipClient}
import io.constellationnetwork.node.shared.infrastructure.mempool.EventMempool
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.node.shared.infrastructure.node.RestartService
import io.constellationnetwork.node.shared.infrastructure.selfhealth.LocalHealthMonitor
import io.constellationnetwork.node.shared.infrastructure.snapshot.{CurrencySnapshotCreator, CurrencySnapshotValidator}
import io.constellationnetwork.node.shared.snapshot.currency._
import io.constellationnetwork.schema.artifact.SharedArtifact
import io.constellationnetwork.schema.balance.Amount
import io.constellationnetwork.schema.gossip.RumorRaw
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, SnapshotOrdinal}
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.{Hashed, HasherSelector, SecurityProvider}

import io.circe.{Decoder, Encoder}
import org.http4s.client.Client
import org.typelevel.log4cats.slf4j.Slf4jLogger

/** Factory for creating the Currency L0 consensus engine.
  *
  * Wires together all components and starts the consensus background stream. Returns a Consensus instance with handler (for gossip),
  * manager (external API), storage (state queries), and routes (HTTP endpoints).
  *
  * @see
  *   ConsensusEventLoop for FSM and command processing
  */
object CurrencySnapshotConsensus {

  def make[F[_]: Async: Parallel: Random: SecurityProvider: Metrics](
    gossip: Gossip[F],
    selfId: PeerId,
    keyPair: KeyPair,
    seedlist: Option[Set[SeedlistEntry]],
    collateral: Amount,
    clusterStorage: ClusterStorage[F],
    nodeStorage: NodeStorage[F],
    lastGlobalSnapshotStorage: LastSyncGlobalSnapshotStorage[F],
    maybeRewards: Option[Rewards[F, CurrencySnapshotStateProof, CurrencyIncrementalSnapshot, CurrencySnapshotEvent]],
    snapshotConfig: SnapshotConfig,
    environment: AppEnvironment,
    client: Client[F],
    session: Session[F],
    stateChannelSnapshotService: StateChannelSnapshotService[F],
    maybeDataApplication: Option[BaseDataApplicationL0Service[F]],
    creator: CurrencySnapshotCreator[F],
    validator: CurrencySnapshotValidator[F],
    hasherSelector: HasherSelector[F],
    restartService: RestartService[F, _],
    leavingDelay: FiniteDuration,
    getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]],
    maybeCustomArtifacts: Option[Signed[CurrencyIncrementalSnapshot] => Option[SortedSet[SharedArtifact]]],
    eventMempool: EventMempool[F, CurrencySnapshotEvent, CurrencyStateKey],
    rumorQueue: Queue[F, Hashed[RumorRaw]],
    // B2 witness channel — see GlobalSnapshotConsensus for the full rationale.
    getPeerChainTips: F[Map[PeerId, ChainTip]],
    // v15 self-health throttle. Injected so the metagraph creator can stamp Facility.selfHealthHint
    // from this node's current LocalHealthMonitor sample. Shared with the dag-l0 instance via
    // SharedServices.localHealthMonitor at the caller site.
    localHealthMonitor: LocalHealthMonitor[F],
    // Dedicated EC for the FSM consume fiber. When provided, the whole
    // `loop.run.compile.drain` is shifted onto this EC via `Async[F].evalOn`, isolating
    // round-timing from HTTP serving load on the default global runtime. When None (default,
    // and what tests use) the loop runs on the ambient runtime. See `ConsensusExecutor`.
    consensusEc: Option[ExecutionContext] = None
  )(implicit supervisor: Supervisor[F]): F[CurrencySnapshotConsensus[F]] = {
    implicit val daDecoder: Decoder[DataTransaction] = DataTransactionCodecs.decoder(maybeDataApplication)
    implicit val daEncoder: Encoder[DataTransaction] = DataTransactionCodecs.encoder(maybeDataApplication)
    implicit val hs: HasherSelector[F] = hasherSelector

    // v20: env-resolved Core committee size threaded into `ConsensusConfig.coreCommitteeSize`
    // so it folds into `deterministicConfigHash`. Mirror of GlobalSnapshotConsensus -- one
    // env-resolution point feeds every downstream component (storage, advancer, state creator,
    // event loop). Default `3` mirrors the dev-environment value.
    val resolvedCoreCommitteeSize: Int =
      snapshotConfig.coreCommitteeSize.get(environment).map(_.value).getOrElse(3)
    // v33: env-resolved quorum-denominator-shrink activation threshold (absent env entry = 0 =
    // rung disabled). Mirror of GlobalSnapshotConsensus.
    val resolvedQuorumShrinkActivationViews: Int =
      snapshotConfig.quorumShrinkActivationViews.get(environment).map(_.value).getOrElse(0)
    // Bounded probation re-entry lane: env-resolved minimum probation slots (absent env entry = 0 =
    // lane inert). Mirror of GlobalSnapshotConsensus; folds into `deterministicConfigHash` via the
    // consensus config copy below.
    val resolvedActiveAdmissionMinProbationReentrySlots: Int =
      snapshotConfig.activeAdmissionMinProbationReentrySlots.get(environment).getOrElse(0)
    // Recent-signer pool lookback depth: env-resolved, floored to DemotionConsecutiveMisses (3) so a
    // low operator value cannot disable the recent-signer path (Codex review #2). Mirror of
    // GlobalSnapshotConsensus; folds into `deterministicConfigHash` via the consensus config copy below.
    val resolvedActiveAdmissionRecentSignerWindow: Int =
      math.max(3, snapshotConfig.activeAdmissionRecentSignerWindow.get(environment).getOrElse(3))
    // Bounded one-slot Tier-1 reward rotation: env-resolved epoch length (absent env entry = 0 =
    // lane inert). Mirror of GlobalSnapshotConsensus; folds into `deterministicConfigHash` via the
    // consensus config copy below.
    val resolvedRewardRotationEpochRounds: Int =
      snapshotConfig.rewardRotationEpochRounds.get(environment).getOrElse(0)
    // Bounded one-slot Tier-1 reward rotation: env-resolved eligibility window (absent env entry =
    // 0 = use the full evidence window). Mirror of GlobalSnapshotConsensus; folds into
    // `deterministicConfigHash` via the consensus config copy below.
    val resolvedRewardRotationEligibilityWindow: Int =
      snapshotConfig.rewardRotationEligibilityWindow.get(environment).getOrElse(0)
    val effectiveConsensusConfig: ConsensusConfig =
      snapshotConfig.consensus.copy(
        coreCommitteeSize = Some(resolvedCoreCommitteeSize),
        quorumShrinkActivationViews = resolvedQuorumShrinkActivationViews,
        activeAdmissionMinProbationReentrySlots = resolvedActiveAdmissionMinProbationReentrySlots,
        activeAdmissionRecentSignerWindow = resolvedActiveAdmissionRecentSignerWindow,
        rewardRotationEpochRounds = resolvedRewardRotationEpochRounds,
        rewardRotationEligibilityWindow = resolvedRewardRotationEligibilityWindow
      )

    for {
      consensusStorage <- ConsensusStorage.make[
        F,
        CurrencySnapshotEvent,
        CurrencySnapshotKey,
        CurrencySnapshotArtifact,
        CurrencySnapshotContext,
        CurrencySnapshotStatus,
        CurrencyConsensusOutcome,
        CurrencyConsensusKind
      ](effectiveConsensusConfig)

      consensusFns =
        CurrencySnapshotConsensusFunctions.make[F](
          collateral,
          maybeRewards,
          creator,
          validator,
          maybeCustomArtifacts
        )

      eventGossipClient = EventGossipClient.make[F, CurrencySnapshotEvent](client, session)

      facilitatorSelector = FacilitatorSelector.make(
        snapshotConfig.maxFacilitatorCount.get(environment).map(_.value)
      )

      consensusStateAdvancer =
        CurrencySnapshotConsensusStateAdvancer.make(
          effectiveConsensusConfig,
          keyPair,
          consensusStorage,
          consensusFns,
          stateChannelSnapshotService,
          gossip,
          maybeDataApplication,
          restartService,
          nodeStorage,
          leavingDelay,
          getGlobalSnapshotByOrdinal,
          clusterStorage,
          eventMempool,
          eventGossipClient,
          facilitatorSelector
        )

      peerQualityTracker <- PeerQualityTracker.make[F]

      tcaFilter = TrailingCommonAncestorFilter.make[F]

      consensusStateCreator =
        CurrencySnapshotConsensusStateCreator.make(
          consensusFns,
          consensusStorage,
          lastGlobalSnapshotStorage,
          gossip,
          selfId,
          seedlist,
          facilitatorSelector,
          effectiveConsensusConfig.deterministicConfigHash,
          effectiveConsensusConfig,
          peerQualityTracker,
          tcaFilter,
          eventMempool,
          localHealthMonitor,
          // v19 per-environment Core floor mirror of dag-l0. v20 routes the env-resolved
          // value through `effectiveConsensusConfig.coreCommitteeSize`, so this binding is
          // what both the state creator and `deterministicConfigHash` see.
          resolvedCoreCommitteeSize
        )

      consensusStateRemover =
        CurrencySnapshotConsensusStateRemover.make(
          consensusStorage,
          gossip
        )

      consensusStatusOps = CurrencySnapshotConsensusOps.make

      stateUpdater =
        ConsensusStateUpdater.make(
          consensusStateAdvancer,
          consensusStorage,
          consensusStatusOps
        )

      consensusClient = ConsensusClient.make[F, CurrencySnapshotKey, CurrencyConsensusOutcome](client, session)

      directPushFn = ConsensusDirectSender.makeDirectPushFn(clusterStorage, consensusClient)
      _ <- gossip.setDirectPushFn(directPushFn)

      viewChangeVoter = new GossipingViewChangeVoter[
        F,
        CurrencySnapshotEvent,
        CurrencySnapshotKey,
        CurrencySnapshotArtifact,
        CurrencySnapshotContext,
        CurrencySnapshotStatus,
        CurrencyConsensusOutcome,
        CurrencyConsensusKind
      ](
        selfId,
        keyPair,
        gossip,
        consensusStorage,
        (o: CurrencyConsensusOutcome) => o.finished.snapshotHash,
        Slf4jLogger.getLogger[F]
      )

      timeoutVoter = new GossipingTimeoutVoter[
        F,
        CurrencySnapshotEvent,
        CurrencySnapshotKey,
        CurrencySnapshotArtifact,
        CurrencySnapshotContext,
        CurrencySnapshotStatus,
        CurrencyConsensusOutcome,
        CurrencyConsensusKind
      ](
        selfId,
        keyPair,
        gossip,
        consensusStorage,
        (o: CurrencyConsensusOutcome) => o.finished.snapshotHash,
        Slf4jLogger.getLogger[F]
      )

      evictionVoter = new GossipingEvictionVoter[
        F,
        CurrencySnapshotEvent,
        CurrencySnapshotKey,
        CurrencySnapshotArtifact,
        CurrencySnapshotContext,
        CurrencySnapshotStatus,
        CurrencyConsensusOutcome,
        CurrencyConsensusKind
      ](
        selfId,
        keyPair,
        gossip,
        consensusStorage,
        (o: CurrencyConsensusOutcome) => o.finished.snapshotHash,
        Slf4jLogger.getLogger[F]
      )

      admissionVoter = new GossipingAdmissionVoter[
        F,
        CurrencySnapshotEvent,
        CurrencySnapshotKey,
        CurrencySnapshotArtifact,
        CurrencySnapshotContext,
        CurrencySnapshotStatus,
        CurrencyConsensusOutcome,
        CurrencyConsensusKind
      ](
        selfId,
        keyPair,
        gossip,
        consensusStorage,
        (o: CurrencyConsensusOutcome) => o.finished.snapshotHash,
        Slf4jLogger.getLogger[F]
      )

      loop <-
        ConsensusEventLoop.build[
          F,
          CurrencySnapshotEvent,
          CurrencySnapshotKey,
          CurrencySnapshotArtifact,
          CurrencySnapshotContext,
          CurrencySnapshotStatus,
          CurrencyConsensusOutcome,
          CurrencyConsensusKind
        ](
          selfId,
          gossip,
          consensusStorage,
          consensusStateCreator,
          stateUpdater,
          consensusStateAdvancer,
          consensusStateRemover,
          consensusStatusOps,
          nodeStorage,
          clusterStorage,
          consensusFns,
          consensusClient,
          effectiveConsensusConfig,
          facilitatorSelector,
          peerQualityTracker,
          viewChangeVoter,
          timeoutVoter,
          evictionVoter,
          admissionVoter,
          (o: CurrencyConsensusOutcome) =>
            !o.recentProofSizes.values.exists(_ >= effectiveConsensusConfig.bootstrapCompleteProofsThreshold),
          (o: CurrencyConsensusOutcome) => o.readmissionCountdown.filter(_._2 > 0).keySet,
          (o: CurrencyConsensusOutcome) => o.finished.snapshotHash,
          (o: CurrencyConsensusOutcome) => o.peerQuality.toMap,
          (o: CurrencyConsensusOutcome) => o.recentRoundEndTimes.lastOption.map(_._2),
          getPeerChainTips
        )

      handler = CurrencyConsensusHandler.make(loop.queue)

      routes = new ConsensusRoutes[
        F,
        CurrencySnapshotKey,
        CurrencySnapshotArtifact,
        CurrencySnapshotContext,
        CurrencySnapshotStatus,
        CurrencyConsensusOutcome,
        CurrencyConsensusKind
      ](consensusStorage, rumorQueue)

      // Pin the consume fiber onto the dedicated consensus EC when one was provided. The
      // shift covers the entire stream's compile-drain so queue.take blocks on the consensus
      // pool rather than the global runtime.
      _ <- supervisor.supervise(consensusEc.fold(loop.run.compile.drain)(ec => Async[F].evalOn(loop.run.compile.drain, ec)))
      triggerEventConsensus = loop.queue.offer(
        ConsensusCommand.FacilitateByEvent
      )
      consensus = new Consensus(
        handler,
        consensusStorage,
        loop.manager,
        routes,
        consensusFns,
        Some(loop.healthRef),
        Some(triggerEventConsensus)
      )
    } yield consensus
  }
}
