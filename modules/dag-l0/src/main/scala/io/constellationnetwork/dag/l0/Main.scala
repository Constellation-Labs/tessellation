package io.constellationnetwork.dag.l0

import cats.Parallel
import cats.effect._
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.BuildInfo
import io.constellationnetwork.dag.l0.StoragesInitializer.initializeStorages
import io.constellationnetwork.dag.l0.cli.method._
import io.constellationnetwork.dag.l0.config.types._
import io.constellationnetwork.dag.l0.domain.snapshot.ForkRecoveryService
import io.constellationnetwork.dag.l0.domain.snapshot.recovery.RecoveryCheckpointLoader
import io.constellationnetwork.dag.l0.http.p2p.P2PClient
import io.constellationnetwork.dag.l0.infrastructure.snapshot.event.GlobalSnapshotEvent
import io.constellationnetwork.dag.l0.infrastructure.snapshot.schema.{Finished, GlobalConsensusOutcome}
import io.constellationnetwork.dag.l0.infrastructure.trust.handler.{ordinalTrustHandler, trustHandler}
import io.constellationnetwork.dag.l0.modules._
import io.constellationnetwork.ext.cats.effect._
import io.constellationnetwork.ext.kryo._
import io.constellationnetwork.node.shared.app.{DagL0, NodeShared, TessellationIOApp}
import io.constellationnetwork.node.shared.domain.collateral.OwnCollateralNotSatisfied
import io.constellationnetwork.node.shared.ext.pureconfig._
import io.constellationnetwork.node.shared.infrastructure.consensus.state._
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.EventTrigger
import io.constellationnetwork.node.shared.infrastructure.consensus.{ConsensusLog, TierTransitions}
import io.constellationnetwork.node.shared.infrastructure.genesis.{GenesisFS => GenesisLoader}
import io.constellationnetwork.node.shared.infrastructure.gossip.event._
import io.constellationnetwork.node.shared.infrastructure.gossip.{GossipDaemon, RumorHandlers}
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.node.shared.infrastructure.snapshot.storage.{GlobalSnapshotLocalFileSystemStorage, PeerHistorySidecarStorage}
import io.constellationnetwork.node.shared.resources.MkHttpServer.ServerName
import io.constellationnetwork.node.shared.resources.{ConsensusExecutor, MkHttpServer}
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.cluster.ClusterId
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.mpt.GlobalStateConverter.syntax._
import io.constellationnetwork.schema.mpt.GlobalStateKey
import io.constellationnetwork.schema.node.NodeState
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.semver.TessellationVersion
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.{Signed, SignedValidator}
import io.constellationnetwork.security.{Hasher, HasherSelector}

import com.monovore.decline.Opts
import eu.timepit.refined.auto._
import eu.timepit.refined.pureconfig._
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import pureconfig.generic.auto._
import pureconfig.module.enumeratum._

object Main
    extends TessellationIOApp[Run](
      name = "dag-l0",
      header = "Tessellation Node",
      version = TessellationVersion.unsafeFrom(BuildInfo.version),
      clusterId = ClusterId("6d7f1d6a-213a-4148-9d45-d7200f555ecf"),
      layer = DagL0
    ) {

  val opts: Opts[Run] = cli.method.opts

  protected val configFiles: List[String] = List("dag-l0.conf")

  private[dag] def rollbackBootstrapFacilitators(nodeId: PeerId, proofSigners: List[PeerId]): List[PeerId] =
    if (proofSigners.nonEmpty) proofSigners else List(nodeId)

  private[dag] final case class RecentCoreReconstructionDiagnostic(
    source: String,
    entries: SortedMap[SnapshotOrdinal, SortedSet[PeerId]]
  )

  private[dag] def reconstructRecentCoreFacilitatorsDiagnostic(
    peerHistorySidecar: PeerHistorySidecarStorage[IO],
    rollbackOrdinal: SnapshotOrdinal,
    rollbackPeerHistory: Option[ConsensusOperationalState],
    windowSize: Int
  ): IO[RecentCoreReconstructionDiagnostic] = {
    def coreFrom(state: ConsensusOperationalState): SortedSet[PeerId] =
      SortedSet.from(state.perPeer.iterator.collect {
        case (pid, record) if record.tier.contains(TierTransitions.Core) => pid
      })

    val start = math.max(0L, rollbackOrdinal.value.value - math.max(0, windowSize - 1).toLong)
    val ordinals = (start to rollbackOrdinal.value.value).toList.flatMap(SnapshotOrdinal(_))

    ordinals.traverse { ordinal =>
      peerHistorySidecar.read(ordinal).map(_.map(state => ordinal -> coreFrom(state)))
    }
      .map(entries => SortedMap.from(entries.flatten))
      .map { sidecarEntries =>
        if (sidecarEntries.nonEmpty) RecentCoreReconstructionDiagnostic("sidecar", sidecarEntries)
        else
          rollbackPeerHistory
            .map(state => RecentCoreReconstructionDiagnostic("replayed", SortedMap(rollbackOrdinal -> coreFrom(state))))
            .getOrElse(RecentCoreReconstructionDiagnostic("unavailable", SortedMap.empty))
      }
  }

  type KryoRegistrationIdRange = DagL0KryoRegistrationIdRange

  val kryoRegistrar: Map[Class[_], KryoRegistrationId[KryoRegistrationIdRange]] =
    dagL0KryoRegistrar

  def run(method: Run, nodeShared: NodeShared[IO, Run]): Resource[IO, Unit] = {
    import nodeShared._

    for {
      cfgR <- loadConfigAs[AppConfigReader].asResource
      implicit0(logger: SelfAwareStructuredLogger[IO]) = Slf4jLogger.getLoggerFromName[IO](this.getClass.getName)
      cfg = method.appConfig(cfgR, sharedConfig)
      queues <- Queues.make[IO](sharedQueues).asResource

      // B2 witness channel: the mesh-gossip peer-chain-tip Ref is created here so it can be
      // closed over by Services (which builds consensus) before EventGossipDaemon exists.
      // Default getter returns Map.empty → no admission votes fire pre-daemon (safe). Once
      // eventGossipDaemon is up below, we `.set(eventGossipDaemon.getPeerChainTips)` so
      // subsequent reads through the thunk return fresh mesh tips.
      peerChainTipsGetterRef <-
        Ref
          .of[IO, IO[Map[PeerId, ChainTip]]](
            Map.empty[PeerId, ChainTip].pure[IO]
          )
          .asResource
      getPeerChainTips = peerChainTipsGetterRef.get.flatten

      p2pClient = P2PClient.make[IO](sharedP2PClient, sharedResources.client, sharedServices.session, sharedConfig.snapshotTimeoutsConfig)
      storages <- Storages
        .make[IO](
          sharedStorages,
          sharedConfig,
          nodeShared.seedlist,
          cfg.snapshot,
          cfg.incremental,
          trustRatings,
          sharedConfig.environment,
          hashSelect
        )
        .asResource
      // Dedicated work-stealing pool for the ConsensusEventLoop consume fiber. Isolates
      // round-timing from HTTP serving load on the default global compute pool. Zero or
      // negative `consensusDispatcherThreads` falls back to the global runtime (legacy
      // behaviour). See ConsensusExecutor for the rationale and lifecycle notes.
      consensusEc <- ConsensusExecutor.optional[IO](cfg.snapshot.consensus.consensusDispatcherThreads)
      services <- Services
        .make[IO, Run](
          sharedConfig,
          sharedServices,
          sharedStorages,
          queues,
          storages,
          nodeShared.sharedValidators,
          sharedResources.client,
          sharedServices.session,
          nodeShared.seedlist,
          method.stateChannelAllowanceLists,
          nodeShared.nodeId,
          keyPair,
          cfg,
          Hasher.forKryo[IO],
          nodeShared.loggerBundle,
          getPeerChainTips,
          consensusEc
        )
        .asResource

      recoveryCheckpoint <- HasherSelector[IO].withCurrent { implicit hasher =>
        RecoveryCheckpointLoader.load[IO](
          cfg.recovery.checkpointPath,
          nodeShared.seedlist.map(_.map(_.peerId)),
          cfg.environment.toString,
          SignedValidator.make[IO]
        )
      }.asResource

      programs = Programs.make[IO, Run](
        sharedPrograms,
        storages,
        services,
        keyPair,
        cfg,
        cfg.incremental.lastFullGlobalSnapshotOrdinal.getOrElse(cfg.environment, SnapshotOrdinal.MinValue),
        p2pClient,
        sharedServices.globalSnapshotContextFns,
        storages.globalSnapshot,
        sharedStorages.lastNGlobalSnapshot,
        sharedStorages.lastGlobalSnapshot,
        sharedStorages.mptStore,
        nodeShared.seedlist.map(_.map(_.peerId)),
        recoveryCheckpoint
      )

      rumorHandler = RumorHandlers
        .make[IO](storages.cluster, services.localHealthcheck)
        .handlers <+>
        trustHandler(storages.trust) <+> ordinalTrustHandler(storages.trust) <+> services.consensus.handler

      forkRecoveryService = ForkRecoveryService.make[IO](
        storages.node,
        sharedStorages.lastGlobalSnapshot,
        services.recoveryPeerHint
      )

      // Tier 2 fork verification: resolves PeerId → Peer, then asks the peer for its snapshot
      // hash at the given ordinal via /global-snapshots/{ordinal}/hash. Returns None on any
      // resolution or RPC failure so the detector treats it as inconclusive (safer than false
      // positives). Reuses the existing p2pClient.globalSnapshot.getHash primitive.
      hashAtOrdinalProbe =
        new HashAtOrdinalProbe[IO] {
          def probe(peerId: PeerId, ordinal: SnapshotOrdinal): IO[Option[Hash]] =
            storages.cluster.getPeer(peerId).flatMap {
              case None => none[Hash].pure[IO]
              case Some(peer) =>
                p2pClient.globalSnapshot.getHash(ordinal).run(peer).attempt.map(_.toOption.flatten)
            }
        }

      eventGossipDaemon <- EventGossipDaemon
        .make[IO, GlobalSnapshotEvent, GlobalStateKey](
          services.eventMempool,
          storages.cluster,
          storages.node,
          sharedResources.gossipClient,
          sharedServices.session,
          config = EventGossipConfig(
            heartbeatInterval = cfg.snapshot.consensus.eventGossipHeartbeatInterval,
            pullInterval = cfg.snapshot.consensus.eventGossipPullInterval
          ),
          getLocalChainTip = Some(forkRecoveryService.getLocalChainTip),
          onForkDetected = Some(forkRecoveryService.onForkDetected),
          forkLagThreshold = cfg.snapshot.consensus.forkLagThreshold,
          verifyHashAt = Some(hashAtOrdinalProbe)
        )
        .asResource
      // B2 witness channel: publish eventGossipDaemon's chain-tip getter into the Ref we
      // created pre-services. Before this runs, the consensus StallDetector sees an empty
      // map (no admission votes fire). After this, mesh chain tips flow into admission
      // emission decisions.
      _ <- Resource.eval(peerChainTipsGetterRef.set(eventGossipDaemon.getPeerChainTips))

      _ <- Daemons
        .start(
          storages,
          services,
          programs,
          queues,
          nodeId,
          keyPair,
          cfg,
          hasherSelector,
          eventGossipDaemon,
          sharedServices.stateEntryAtRef
        )
        .asResource

      api <- Resource.eval(
        HttpApi.make[IO, Run](
          storages,
          queues,
          services,
          programs,
          keyPair.getPrivate,
          sharedConfig.environment,
          nodeShared.nodeId,
          TessellationVersion.unsafeFrom(BuildInfo.version),
          cfg.http,
          sharedValidators,
          cfg.shared.delegatedStaking.withdrawalTimeLimit
            .getOrElse(sharedConfig.environment, EpochProgress.MinValue),
          cfg.shared,
          storages.combinedGlobalSnapshotCheckpointStorage,
          sharedStorages.lastNGlobalSnapshot,
          getLocalChainTip = Some(forkRecoveryService.getLocalChainTip),
          maybeMarkSeen = Some(eventGossipDaemon.markSeen)
        )
      )

      // Alpha.95: env-resolved listener caps. Lifts the alpha.76 blanket maxConnections=100
      // ceiling that caused the May 17 testnet regression by saturating p2p sockets under
      // 13+ peer load. See `HttpMaxConnectionsDefaults` for per-env values.
      httpResolved = cfg.http.envResolved(cfg.environment)
      _ <- MkHttpServer[IO].newEmber(ServerName("public"), httpResolved.publicHttp, api.publicApp)
      _ <- MkHttpServer[IO].newEmber(ServerName("p2p"), httpResolved.p2pHttp, api.p2pApp)
      _ <- MkHttpServer[IO].newEmber(ServerName("cli"), httpResolved.cliHttp, api.cliApp)

      gossipDaemon = GossipDaemon.make[IO](
        storages.rumor,
        queues.rumor,
        storages.cluster,
        p2pClient.gossip,
        rumorHandler,
        nodeShared.sharedValidators.rumorValidator,
        services.localHealthcheck,
        nodeId,
        generation,
        sharedConfig.gossip.daemon,
        services.collateral
      )

      _ <- (method match {
        case m: RunValidator =>
          storages.node.setValidatorMode >>
            gossipDaemon.startAsRegularValidator >>
            storages.node.tryModifyState(NodeState.Initial, NodeState.ReadyToJoin) >>
            services.restart.setNodeForkedRestartMethod(
              RunValidatorWithJoinAttempt(
                m.keyStore,
                m.alias,
                m.password,
                m.dbConfig,
                m.httpConfig,
                m.environment,
                m.seedlistPath,
                m.collateralAmount,
                m.trustRatingsPath,
                m.prioritySeedlistPath,
                _,
                m.allowanceListPath
              )
            )
        case m: RunValidatorWithJoinAttempt =>
          storages.node.setValidatorMode >>
            gossipDaemon.startAsRegularValidator >>
            storages.node.tryModifyState(NodeState.Initial, NodeState.ReadyToJoin) >>
            programs.joining.joinOneOf(m.peerToJoinPool) >>
            services.restart.setClusterLeaveRestartMethod(
              RunValidator(
                m.keyStore,
                m.alias,
                m.password,
                m.dbConfig,
                m.httpConfig,
                m.environment,
                m.seedlistPath,
                m.collateralAmount,
                m.trustRatingsPath,
                m.prioritySeedlistPath,
                m.allowanceListPath
              )
            ) >>
            services.restart.setNodeForkedRestartMethod(
              RunValidatorWithJoinAttempt(
                m.keyStore,
                m.alias,
                m.password,
                m.dbConfig,
                m.httpConfig,
                m.environment,
                m.seedlistPath,
                m.collateralAmount,
                m.trustRatingsPath,
                m.prioritySeedlistPath,
                _,
                m.allowanceListPath
              )
            )
        case m: RunRollback =>
          storages.node.tryModifyState(
            NodeState.Initial,
            NodeState.RollbackInProgress,
            NodeState.RollbackDone
          ) {
            programs.rollbackLoader.load(m.rollbackHash, programs.download).flatMap {
              case (snapshotInfo, snapshot) =>
                for {
                  hashedSnapshot <- hasherSelector.withCurrent(implicit hasher => snapshot.toHashed[IO])
                  // Rollback bootstrap: preserve the rolled-back snapshot's proof signers as
                  // the checkpoint's live seed committee. That keeps lastSigners/Core anchored
                  // to signed evidence instead of turning a non-signer rollback server into a
                  // self-only chain tip. Only fall back to self-only when the checkpoint has no
                  // proofs at all (genesis / malformed legacy edge).
                  proofSigners = snapshot.proofs.toSortedSet.toList.map(_.id.toPeerId)
                  bootstrapFacilitators = rollbackBootstrapFacilitators(nodeId, proofSigners)
                  bootstrapMode = if (bootstrapFacilitators === proofSigners) "proof_signers" else "self_only_fallback"
                  _ <- ConsensusLog.info(
                    logger,
                    ConsensusLog.Category.Recovery,
                    snapshot.ordinal.toString,
                    "n/a",
                    ConsensusLog.Event.DownloadInitStart,
                    "reason" -> "rollback_bootstrap_facilitators",
                    "mode" -> bootstrapMode,
                    "proofSignerCount" -> proofSigners.size.toString,
                    "bootstrapFacilitatorCount" -> bootstrapFacilitators.size.toString,
                    "selfSignedCheckpoint" -> proofSigners.contains(nodeId).toString,
                    "proofSigners" -> proofSigners.map(ConsensusLog.pid).sorted.mkString(","),
                    "bootstrapFacilitators" -> bootstrapFacilitators.map(ConsensusLog.pid).sorted.mkString(",")
                  )
                  _ <- Metrics[IO].incrementCounter(
                    "dag_consensus_rollback_bootstrap_total",
                    Seq(Metrics.unsafeLabelName("mode") -> bootstrapMode)
                  )
                  _ <- Metrics[IO].updateGauge("dag_consensus_rollback_proof_signer_count", proofSigners.size.toLong)
                  _ <- Metrics[IO].updateGauge("dag_consensus_rollback_bootstrap_facilitator_count", bootstrapFacilitators.size.toLong)
                  // Seed the bootstrap-warmup window with the rollback snapshot's proof count.
                  // If we're rolling back to a healthy multi-node snapshot (proofs.size >= threshold),
                  // the window classifies as post-bootstrap and penalties apply immediately. If we're
                  // rolling back to a solo/bootstrap-era snapshot, the window starts in bootstrap mode
                  // and the cluster re-stabilizes naturally.
                  rollbackProofSize = snapshot.proofs.size.toInt
                  // Persisted operational history if the rollback snapshot carries it.
                  // Older snapshots have `peerHistory = None`, so seedOperational stays empty
                  // and the cluster bootstraps from zero. Newer snapshots restore
                  // chronic-classifier history, B2 readmission, and removal-penalty
                  // escalation across the cold-restart boundary.
                  //
                  // Alpha.94: prefer the post-finalization peerHistory sidecar written by
                  // `GlobalSnapshotConsensusStateAdvancer.persistAndGossip` on the previous run.
                  // The signed `snapshot[N].peerHistory` field is packed at round-N proposal time
                  // (before Outcome[N] existed) so it actually carries `pack(Outcome[N-1])`. The
                  // sidecar at `<snapshotPath>/peerHistory/<ordinal>.meta` contains
                  // `pack(Outcome[N])`, eliminating the previously-documented one-round-stale
                  // seed. Pre-v19 this stale drift was harmless (below the chronic-classifier
                  // floor of 10-30 observations); post-v19 the same fields drive eligible-
                  // facilitator computation, so the stale seed produced the
                  // `facilitator_set_mismatch_revalidate` wedge observed at ord 3127130 (see
                  // `project_alpha92_wedge_may21.md`). On missing/malformed sidecar the read
                  // returns None and we fall back to `snapshot.peerHistory` -- pre-alpha.94
                  // behavior, which is the right thing for snapshots written by older nodes.
                  peerHistorySidecar <- PeerHistorySidecarStorage.make[IO](cfg.snapshot.snapshotPath / "peerHistory")
                  sidecarPeerHistory <- peerHistorySidecar.read(snapshot.value.ordinal)
                  seedOperational = sidecarPeerHistory
                    .orElse(snapshot.value.peerHistory)
                    .getOrElse(ConsensusOperationalState.empty)
                  // Project the consolidated per-peer record back out to the five PeerId-keyed
                  // dimensions on the outcome. A peer absent from `perPeer` is treated as
                  // `PerPeerOperationalRecord.empty` (= no penalty, no probation, etc.) on the
                  // consumer side; here we only emit non-default entries to keep the maps small.
                  seedPeerQuality = SortedMap.from(seedOperational.perPeer.iterator.collect {
                    case (pid, r) if r.quality != ((0, 0)) => pid -> r.quality
                  })
                  seedRemovalPenalties = SortedMap.from(seedOperational.perPeer.iterator.collect {
                    case (pid, r) if r.removalPenalty > 0 => pid -> r.removalPenalty
                  })
                  seedCumulativeMissCounts = SortedMap.from(seedOperational.perPeer.iterator.collect {
                    case (pid, r) if r.cumulativeMissCount > 0L => pid -> r.cumulativeMissCount
                  })
                  seedReadmissionCountdown = SortedMap.from(seedOperational.perPeer.iterator.collect {
                    case (pid, r) if r.readmissionCountdown > 0 => pid -> r.readmissionCountdown
                  })
                  seedDeferralCountdown = SortedMap.from(seedOperational.perPeer.iterator.collect {
                    case (pid, r) if r.deferralCountdown > 0 => pid -> r.deferralCountdown
                  })
                  // v16: per-peer cumulative view-change-caused. Seeded only when non-zero so
                  // the persisted map stays small. Absent peers default to 0 at the v16 filter
                  // call-site, matching the pre-v16 "no penalty" semantic.
                  // PerPeerOperationalRecord.viewChangesCaused is Option[Long] (back-compat with
                  // pre-v16 JSON), so unwrap with the same > 0 filter.
                  seedPeerViewChanges = SortedMap.from(seedOperational.perPeer.iterator.flatMap {
                    case (pid, r) => r.viewChangesCaused.filter(_ > 0L).map(v => pid -> v)
                  })
                  // Recent-proof window: prefer the persisted history (so the bootstrap-vs-post
                  // classification matches the running cluster), otherwise seed with the rollback
                  // snapshot's proof count -- preserves pre-v20 behavior.
                  seedRecentProofSizes =
                    if (seedOperational.recentProofSizes.nonEmpty) seedOperational.recentProofSizes
                    else SortedMap(snapshot.ordinal -> rollbackProofSize)
                  // Recent-signers window unwrapped from the Option. Snapshots written before
                  // the field existed decode None -> empty map; FacilitatorSelector treats an
                  // empty window as bootstrap (use full eligibility until the window fills).
                  seedRecentSigners = seedOperational.recentSigners.getOrElse(SortedMap.empty[SnapshotOrdinal, SortedSet[PeerId]])
                  // v19: per-peer tier classification seeded from `PerPeerOperationalRecord.tier`.
                  // Tier is Option[Int], so peers with `None` are absent from this map and
                  // CommitteeBuilder defaults them to bootstrap-Tier-2 at consume time.
                  seedPeerTiers = SortedMap.from(seedOperational.perPeer.iterator.flatMap {
                    case (pid, r) => r.tier.map(t => pid -> t)
                  })
                  seedActiveAdmissionScores = SortedMap.from(seedOperational.perPeer.iterator.flatMap {
                    case (pid, r) => r.activeAdmissionScore.filter(_ > 0).map(score => pid -> score)
                  })
                  // v19 phase 2: view-from-time window unwrapped from the Option. Snapshots
                  // written before the field existed decode None -> empty map; the next
                  // round's view derivation falls back to phase 1 `viewChangeVotes.maxToView`
                  // until the window populates.
                  seedRecentRoundEndTimes =
                    seedOperational.recentRoundEndTimes.getOrElse(SortedMap.empty[SnapshotOrdinal, Long])
                  // Stage 4: controller-evidence window + cert-anchored penalty horizons,
                  // kept Option-shaped end to end (the outcome fields are Option too).
                  // Pre-deploy snapshots decode None -> the evidence read side stays in its
                  // carried-map fallback regime until the window refills; `filter(_.nonEmpty)`
                  // normalizes a defensive Some(empty) back to None.
                  seedControllerEvidence = seedOperational.controllerEvidence.filter(_.nonEmpty)
                  seedPenaltyUntil = seedOperational.penaltyUntil.filter(_.nonEmpty)
                  recentCoreDiagnostic <- reconstructRecentCoreFacilitatorsDiagnostic(
                    peerHistorySidecar,
                    snapshot.value.ordinal,
                    snapshot.value.peerHistory,
                    cfg.snapshot.consensus.tighteningWindow
                  )
                  recentCoreSummary = recentCoreDiagnostic.entries.toList.map {
                    case (ordinal, core) =>
                      s"${ordinal.value.value}:${core.size}:${core.toList.map(ConsensusLog.pid).mkString(",")}"
                  }
                    .mkString(";")
                  _ <- ConsensusLog.info(
                    logger,
                    ConsensusLog.Category.Recovery,
                    snapshot.ordinal.toString,
                    "n/a",
                    ConsensusLog.Event.RollbackBootstrapActive,
                    "reason" -> "recent_core_facilitators_reconstruction",
                    "source" -> recentCoreDiagnostic.source,
                    "entries" -> recentCoreDiagnostic.entries.size.toString,
                    "windowSize" -> cfg.snapshot.consensus.tighteningWindow.toString,
                    "method" -> "best_effort_effective_tier_core",
                    "summary" -> recentCoreSummary
                  )
                  _ <- Metrics[IO].incrementCounter(
                    "dag_consensus_recent_core_reconstruction_total",
                    Seq(Metrics.unsafeLabelName("source") -> recentCoreDiagnostic.source)
                  )
                  _ <- Metrics[IO].updateGauge(
                    "dag_consensus_recent_core_reconstruction_entries",
                    recentCoreDiagnostic.entries.size.toLong
                  )
                  _ <- Metrics[IO].updateGauge(
                    "dag_consensus_recent_core_reconstruction_latest_core_size",
                    recentCoreDiagnostic.entries.lastOption.map(_._2.size.toLong).getOrElse(0L)
                  )
                  result <- services.consensus.manager.startFacilitatingAfterRollback(
                    snapshot.ordinal,
                    GlobalConsensusOutcome(
                      snapshot.ordinal,
                      Facilitators(bootstrapFacilitators),
                      RemovedFacilitators.empty,
                      WithdrawnFacilitators.empty,
                      EligibleFacilitators(bootstrapFacilitators),
                      Finished(snapshot, snapshotInfo, EventTrigger, Candidates.empty, Hash.empty, hashedSnapshot.hash),
                      removalPenalties = seedRemovalPenalties,
                      deferralCountdown = seedDeferralCountdown,
                      peerQuality = seedPeerQuality,
                      cumulativeMissCounts = seedCumulativeMissCounts,
                      recentProofSizes = seedRecentProofSizes,
                      readmissionCountdown = seedReadmissionCountdown,
                      peerViewChanges = seedPeerViewChanges,
                      recentSigners = seedRecentSigners,
                      peerTiers = seedPeerTiers,
                      activeAdmissionScores = seedActiveAdmissionScores,
                      recentRoundEndTimes = seedRecentRoundEndTimes,
                      controllerEvidence = seedControllerEvidence,
                      penaltyUntil = seedPenaltyUntil
                    ),
                    deferFirstRound = true
                  )
                } yield result
            }
          } >>
            services.collateral
              .hasCollateral(nodeShared.nodeId)
              .flatMap(OwnCollateralNotSatisfied.raiseError[IO, Unit].unlessA) >>
            gossipDaemon.startAsInitialValidator >>
            services.cluster.createSession >>
            services.session.createSession >>
            storages.node.setNodeState(NodeState.Ready) >>
            services.restart.setClusterLeaveRestartMethod(
              RunValidator(
                m.keyStore,
                m.alias,
                m.password,
                m.dbConfig,
                m.httpConfig,
                m.environment,
                m.seedlistPath,
                m.collateralAmount,
                m.trustRatingsPath,
                m.prioritySeedlistPath,
                m.allowanceListPath
              )
            ) >>
            services.restart.setNodeForkedRestartMethod(
              RunValidatorWithJoinAttempt(
                m.keyStore,
                m.alias,
                m.password,
                m.dbConfig,
                m.httpConfig,
                m.environment,
                m.seedlistPath,
                m.collateralAmount,
                m.trustRatingsPath,
                m.prioritySeedlistPath,
                _,
                m.allowanceListPath
              )
            )
        case m: RunGenesis =>
          storages.node.tryModifyState(
            NodeState.Initial,
            NodeState.LoadingGenesis,
            NodeState.GenesisReady
          ) {
            GenesisLoader.make[IO, GlobalSnapshot].loadBalances(m.genesisPath).flatMap { accounts =>
              val genesis = GlobalSnapshot.mkGenesis(
                accounts.map(a => (a.address, a.balance)).toMap,
                m.startingEpochProgress
              )

              hasherSelector.withCurrent { implicit hasher =>
                Signed
                  .forAsyncHasher[IO, GlobalSnapshot](genesis, keyPair)
                  .flatMap(_.toHashed[IO])
              }.flatMap { hashedGenesis =>
                GlobalSnapshotLocalFileSystemStorage.make[IO](cfg.snapshot.snapshotPath).flatMap {
                  fullGlobalSnapshotLocalFileSystemStorage =>
                    hasherSelector.withCurrent { implicit hasher =>
                      fullGlobalSnapshotLocalFileSystemStorage.write(hashedGenesis.signed) >>
                        GlobalSnapshot.mkFirstIncrementalSnapshot[IO](hashedGenesis).flatMap { firstIncrementalSnapshot =>
                          Signed.forAsyncHasher[IO, GlobalIncrementalSnapshot](firstIncrementalSnapshot, keyPair).flatMap {
                            signedFirstIncrementalSnapshot =>
                              for {
                                _ <- services.collateral
                                  .hasCollateral(nodeShared.nodeId)
                                  .flatMap(OwnCollateralNotSatisfied.raiseError[IO, Unit].unlessA)
                                hashedSnapshot <- signedFirstIncrementalSnapshot.toHashed[IO]
                                globalSnapshotInfo = hashedGenesis.info.toGlobalSnapshotInfo
                                _ <- initializeStorages[IO](
                                  storages.globalSnapshot,
                                  sharedStorages.lastNGlobalSnapshot,
                                  sharedStorages.lastGlobalSnapshot,
                                  programs.download,
                                  hashedSnapshot,
                                  globalSnapshotInfo
                                )
                                kvPairs <- globalSnapshotInfo.allStateEntries[IO](
                                  Async[IO],
                                  Parallel[IO],
                                  hasher,
                                  jsonSerializer,
                                  globalStateProofSelector
                                )
                                _ <- sharedStorages.mptStore.syncFull(kvPairs, hashedSnapshot.ordinal)

                                genesisSigners = signedFirstIncrementalSnapshot.proofs.toSortedSet.toList.map(_.id.toPeerId)
                                // Genesis path — a fresh chain start, always classify as bootstrap.
                                // Seed window with the genesis snapshot's proof count (typically 1 for
                                // genesis, which correctly triggers warmup for the initial cluster bring-up).
                                genesisRecentProofSizes = SortedMap(
                                  signedFirstIncrementalSnapshot.ordinal -> signedFirstIncrementalSnapshot.proofs.size.toInt
                                )
                                _ <- services.consensus.manager
                                  .startFacilitatingAfterRollback(
                                    signedFirstIncrementalSnapshot.ordinal,
                                    GlobalConsensusOutcome(
                                      signedFirstIncrementalSnapshot.ordinal,
                                      Facilitators(genesisSigners),
                                      RemovedFacilitators.empty,
                                      WithdrawnFacilitators.empty,
                                      EligibleFacilitators(genesisSigners),
                                      Finished(
                                        signedFirstIncrementalSnapshot,
                                        hashedGenesis.info.toGlobalSnapshotInfo,
                                        EventTrigger,
                                        Candidates.empty,
                                        Hash.empty,
                                        hashedSnapshot.hash
                                      ),
                                      recentProofSizes = genesisRecentProofSizes
                                    ),
                                    // Genesis is also a checkpoint-serving bootstrap. If the
                                    // genesis node starts round 2 immediately, it can finalize a
                                    // self-only snapshot before joining validators register, which
                                    // makes `lastSigners` and Core collapse to one peer forever.
                                    // Deferring gives validators time to download the genesis
                                    // outcome, promote Ready, and register as candidates.
                                    deferFirstRound = true
                                  )
                              } yield ()
                          }
                        }
                    }
                }
              }
            }
          } >>
            gossipDaemon.startAsInitialValidator >>
            services.cluster.createSession >>
            services.session.createSession >>
            storages.node.setNodeState(NodeState.Ready) >>
            services.restart.setClusterLeaveRestartMethod(
              RunValidator(
                m.keyStore,
                m.alias,
                m.password,
                m.dbConfig,
                m.httpConfig,
                m.environment,
                m.seedlistPath,
                m.collateralAmount,
                m.trustRatingsPath,
                m.prioritySeedlistPath,
                m.allowanceListPath
              )
            ) >>
            services.restart.setNodeForkedRestartMethod(
              RunValidatorWithJoinAttempt(
                m.keyStore,
                m.alias,
                m.password,
                m.dbConfig,
                m.httpConfig,
                m.environment,
                m.seedlistPath,
                m.collateralAmount,
                m.trustRatingsPath,
                m.prioritySeedlistPath,
                _,
                m.allowanceListPath
              )
            )
      }).asResource
    } yield ()
  }
}
